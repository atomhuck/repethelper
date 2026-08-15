package ru.repethelper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LessonSubscriptionService {
    private final LessonSubscriptionRepository subscriptions;
    private final LessonSubscriptionCreditRepository credits;
    private final LessonRepository lessons;
    private final ConnectionRequestRepository connections;
    private final AppNotificationService notifications;
    private final Clock clock;
    private final boolean enabled;

    public LessonSubscriptionService(LessonSubscriptionRepository subscriptions,
                                     LessonSubscriptionCreditRepository credits,
                                     LessonRepository lessons,
                                     ConnectionRequestRepository connections,
                                     AppNotificationService notifications,
                                     Clock clock,
                                     @Value("${app.subscriptions.enabled:true}") boolean enabled) {
        this.subscriptions = subscriptions;
        this.credits = credits;
        this.lessons = lessons;
        this.connections = connections;
        this.notifications = notifications;
        this.clock = clock;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    @Transactional
    public LessonSubscription create(User teacher, Long studentId, int lessonCount, int totalRubles) {
        requireEnabled();
        User student = requireAcceptedStudent(teacher, studentId);
        LessonSubscription subscription = subscriptions.save(
                new LessonSubscription(teacher, student, lessonCount, totalRubles, clock.instant()));
        subscriptions.flush();
        notifications.subscriptionCreated(subscription);
        return subscription;
    }

    @Transactional
    public CreationResult createAndAllocate(User teacher, Long studentId, int lessonCount, int totalRubles,
                                            boolean applyToNearest) {
        LessonSubscription subscription = create(teacher, studentId, lessonCount, totalRubles);
        int attached = applyToNearest ? allocateNearest(teacher, subscription, subscription.getTotalLessons()) : 0;
        return new CreationResult(subscription, attached);
    }

    @Transactional
    public int allocateNearest(User teacher, LessonSubscription subscription, int maximum) {
        requireOwned(teacher, subscription);
        List<Lesson> candidates = lessons.findByTeacherAndStudentOrderByStartAtAsc(teacher, subscription.getStudent())
                .stream()
                .filter(item -> item.getStatus() == LessonStatus.SCHEDULED)
                .filter(item -> !item.isPaidBySubscription())
                .filter(item -> item.getPaymentStatus() != PaymentStatus.PAID)
                .sorted(Comparator.comparing(Lesson::getStartAt).thenComparing(Lesson::getId))
                .toList();
        int attached = 0;
        for (Lesson candidate : candidates) {
            if (attached >= maximum || !attachOldestAvailable(teacher, candidate)) break;
            attached++;
        }
        return attached;
    }

    @Transactional
    public boolean attachOldestAvailable(User teacher, Lesson lesson) {
        requireEnabled();
        requireOwnedLesson(teacher, lesson);
        if (lesson.isPaidBySubscription()) return true;
        if (lesson.getPaymentStatus() == PaymentStatus.PAID)
            throw new IllegalArgumentException("Занятие уже отмечено оплаченным другим способом");
        List<LessonSubscriptionCredit> available = credits.findAvailableForUpdate(teacher, lesson.getStudent());
        if (available.isEmpty()) return false;
        lesson.attachSubscriptionCredit(available.getFirst());
        lessons.saveAndFlush(lesson);
        return true;
    }

    @Transactional
    public Lesson attach(User teacher, Long lessonId) {
        Lesson lesson = requireOwnedLessonLocked(teacher, lessonId);
        if (!attachOldestAvailable(teacher, lesson))
            throw new IllegalArgumentException("В активных абонементах нет свободных занятий");
        return lesson;
    }

    @Transactional
    public Lesson release(User teacher, Long lessonId) {
        Lesson lesson = requireOwnedLessonLocked(teacher, lessonId);
        if (!lesson.isPaidBySubscription()) return lesson;
        LessonSubscriptionCredit credit = credits.findLockedById(lesson.getSubscriptionCredit().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        if (credit.isConsumed()) throw new IllegalArgumentException("Списанное место уже нельзя вернуть");
        lesson.releaseSubscriptionCredit();
        lessons.saveAndFlush(lesson);
        return lesson;
    }

    @Transactional
    public Lesson markNoShow(User teacher, Long lessonId) {
        Lesson lesson = requireOwnedLessonLocked(teacher, lessonId);
        if (!lesson.isPaidBySubscription())
            throw new IllegalArgumentException("Пропуск можно списать только у занятия по абонементу");
        if (lesson.getStartAt().isAfter(clock.instant()))
            throw new IllegalArgumentException("Списать пропуск можно после начала занятия");
        LessonSubscriptionCredit credit = credits.findLockedById(lesson.getSubscriptionCredit().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        credit.consumeAsNoShow(lesson.getStartAt(), clock.instant());
        credits.saveAndFlush(credit);
        return lesson;
    }

    @Transactional
    public void cancelRemaining(User teacher, Long subscriptionId) {
        requireEnabled();
        LessonSubscription subscription = requireOwnedLocked(teacher, subscriptionId);
        if (subscription.isCancelled()) return;
        if (availableCount(subscription) == 0)
            throw new IllegalArgumentException("В абонементе нет свободного остатка");
        subscription.cancelRemaining(clock.instant());
    }

    @Transactional
    public void deleteEmpty(User teacher, Long subscriptionId) {
        requireEnabled();
        LessonSubscription subscription = requireOwnedLocked(teacher, subscriptionId);
        if (!lessons.findBySubscriptionId(subscriptionId).isEmpty()
                || subscription.getCredits().stream().anyMatch(LessonSubscriptionCredit::isConsumed)) {
            throw new IllegalArgumentException("Абонемент уже использовался — можно только отменить его остаток");
        }
        subscriptions.delete(subscription);
    }

    @Transactional(readOnly = true)
    public PairSummary summary(User teacher, User student) {
        if (!enabled) return new PairSummary(0, 0, 0, List.of());
        requireTeacher(teacher);
        List<LessonSubscription> packages = subscriptions.findByTeacherAndStudentOrderByCreatedAtAscIdAsc(teacher, student);
        return summarize(packages, linkedLessons(packages));
    }

    @Transactional(readOnly = true)
    public Map<Long, PairSummary> summariesForStudents(User teacher, List<User> students) {
        if (!enabled) return Map.of();
        requireTeacher(teacher);
        Map<Long, PairSummary> result = new LinkedHashMap<>();
        if (students.isEmpty()) return result;
        List<LessonSubscription> all = subscriptions.findByTeacherAndStudentInOrderByCreatedAtAscIdAsc(teacher, students);
        List<Lesson> linked = linkedLessons(all);
        Map<Long, List<LessonSubscription>> byStudent = all.stream()
                .collect(Collectors.groupingBy(item -> item.getStudent().getId(), LinkedHashMap::new, Collectors.toList()));
        for (User student : students) {
            List<LessonSubscription> packages = byStudent.getOrDefault(student.getId(), List.of());
            Set<Long> packageIds = packages.stream().map(LessonSubscription::getId).collect(Collectors.toSet());
            result.put(student.getId(), summarize(packages, linked.stream()
                    .filter(item -> packageIds.contains(item.getSubscriptionCredit().getSubscription().getId())).toList()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<StudentTeacherSummary> summariesForStudent(User student) {
        if (!enabled || student.getRole() != Role.STUDENT) return List.of();
        List<LessonSubscription> all = subscriptions.findByStudentOrderByCreatedAtAscIdAsc(student);
        List<Lesson> linked = linkedLessons(all);
        return all.stream()
                .collect(Collectors.groupingBy(item -> item.getTeacher().getId(), LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(items -> {
                    Set<Long> ids = items.stream().map(LessonSubscription::getId).collect(Collectors.toSet());
                    PairSummary full = summarize(items, linked.stream()
                            .filter(lesson -> ids.contains(lesson.getSubscriptionCredit().getSubscription().getId())).toList());
                    return new StudentTeacherSummary(items.getFirst().getTeacher(),
                            new StudentPairSummary(full.available(), full.planned(), full.used()));
                })
                .filter(item -> item.summary().available() > 0 || item.summary().planned() > 0)
                .toList();
    }

    @Transactional
    public void deleteForPair(User teacher, User student) {
        subscriptions.deleteByTeacherAndStudent(teacher, student);
    }

    private PairSummary summarize(List<LessonSubscription> packages, List<Lesson> linkedLessons) {
        Map<Long, Lesson> linked = linkedLessons.stream()
                .collect(Collectors.toMap(item -> item.getSubscriptionCredit().getId(), Function.identity()));
        Instant now = clock.instant();
        int available = 0, planned = 0, used = 0;
        List<PackageSummary> packageSummaries = new ArrayList<>();
        for (LessonSubscription subscription : packages) {
            int packageAvailable = 0, packagePlanned = 0, packageUsed = 0;
            List<CreditEntry> history = new ArrayList<>();
            for (LessonSubscriptionCredit credit : subscription.getCredits()) {
                Lesson lesson = linked.get(credit.getId());
                if (credit.isConsumed()) {
                    packageUsed++;
                    history.add(new CreditEntry(credit.getOrdinal(), CreditState.NO_SHOW,
                            null, credit.getConsumedLessonStartAt()));
                } else if (lesson != null && !lesson.getEndAt().isAfter(now)) {
                    packageUsed++;
                    history.add(new CreditEntry(credit.getOrdinal(), CreditState.USED, lesson.getId(), lesson.getStartAt()));
                } else if (lesson != null) {
                    packagePlanned++;
                    history.add(new CreditEntry(credit.getOrdinal(), CreditState.PLANNED, lesson.getId(), lesson.getStartAt()));
                } else if (!subscription.isCancelled()) packageAvailable++;
            }
            available += packageAvailable;
            planned += packagePlanned;
            used += packageUsed;
            history.sort(Comparator.comparing(CreditEntry::startAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(CreditEntry::ordinal));
            packageSummaries.add(new PackageSummary(subscription, packageAvailable, packagePlanned, packageUsed, history));
        }
        return new PairSummary(available, planned, used, packageSummaries);
    }

    private List<Lesson> linkedLessons(List<LessonSubscription> packages) {
        List<Long> ids = packages.stream().map(LessonSubscription::getId).toList();
        return ids.isEmpty() ? List.of() : lessons.findBySubscriptionIds(ids);
    }

    private int availableCount(LessonSubscription subscription) {
        Set<Long> linked = lessons.findBySubscriptionId(subscription.getId()).stream()
                .map(item -> item.getSubscriptionCredit().getId()).collect(Collectors.toSet());
        return (int) subscription.getCredits().stream()
                .filter(item -> !item.isConsumed() && !linked.contains(item.getId())).count();
    }

    private LessonSubscription requireOwnedLocked(User teacher, Long id) {
        requireTeacher(teacher);
        LessonSubscription subscription = subscriptions.findLockedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireOwned(teacher, subscription);
        return subscription;
    }

    private void requireOwned(User teacher, LessonSubscription subscription) {
        requireTeacher(teacher);
        if (!subscription.getTeacher().getId().equals(teacher.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Lesson requireOwnedLessonLocked(User teacher, Long id) {
        requireTeacher(teacher);
        Lesson lesson = lessons.findLockedWithStudentById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireOwnedLesson(teacher, lesson);
        return lesson;
    }

    private void requireOwnedLesson(User teacher, Lesson lesson) {
        requireTeacher(teacher);
        if (!lesson.getTeacher().getId().equals(teacher.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private User requireAcceptedStudent(User teacher, Long studentId) {
        requireTeacher(teacher);
        return connections.findByStudentIdAndTeacherAndStatus(studentId, teacher, ConnectionStatus.ACCEPTED)
                .map(ConnectionRequest::getStudent)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void requireTeacher(User user) {
        if (user == null || user.getRole() != Role.TEACHER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public record PairSummary(int available, int planned, int used, List<PackageSummary> packages) {}
    public record CreationResult(LessonSubscription subscription, int attachedLessons) {}
    public enum CreditState { PLANNED, USED, NO_SHOW }
    public record CreditEntry(int ordinal, CreditState state, Long lessonId, Instant startAt) {}
    public record PackageSummary(LessonSubscription subscription, int available, int planned, int used,
                                 List<CreditEntry> history) {}
    public record StudentPairSummary(int available, int planned, int used) {}
    public record StudentTeacherSummary(User teacher, StudentPairSummary summary) {}
}
