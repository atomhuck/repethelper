package ru.repethelper.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class LessonService {
    private final LessonRepository lessons;
    private final UserRepository users;
    private final ConnectionRequestRepository connections;
    private final LessonSeriesRepository seriesRepository;
    private final LessonPaymentRecordRepository paymentRecords;
    private final AttachmentRepository attachments;
    private final WhiteboardService whiteboards;
    private final AppNotificationService notifications;
    private final LessonSubscriptionService subscriptions;
    private final Path storageRoot;
    private final ZoneId zone;
    private final Clock clock;
    public LessonService(LessonRepository lessons, UserRepository users, ConnectionRequestRepository connections,
                         LessonSeriesRepository seriesRepository, LessonPaymentRecordRepository paymentRecords,
                         AttachmentRepository attachments, WhiteboardService whiteboards,
                         AppNotificationService notifications, LessonSubscriptionService subscriptions,
                         @org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone,
                         @org.springframework.beans.factory.annotation.Value("${app.storage-path}") String storagePath,
                         Clock clock) {
        this.lessons = lessons; this.users = users; this.connections = connections; this.seriesRepository = seriesRepository;
        this.paymentRecords = paymentRecords;
        this.attachments = attachments; this.whiteboards = whiteboards; this.notifications = notifications;
        this.subscriptions = subscriptions;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        this.zone = ZoneId.of(timezone); this.clock = clock;
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration, LessonRecurrence recurrence) {
        return create(teacher, studentId, localStart, duration, recurrence, null);
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration,
                         LessonRecurrence recurrence, Integer priceRubles) {
        return create(teacher, studentId, localStart, duration, recurrence, priceRubles,
                LessonPaymentMode.SINGLE, null, null, false, null);
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration,
                         LessonRecurrence recurrence, Integer priceRubles, LessonPaymentMode paymentMode,
                         Integer subscriptionLessonCount, Integer subscriptionTotalRubles,
                         boolean useSubscriptionForSeries) {
        return create(teacher, studentId, localStart, duration, recurrence, priceRubles, paymentMode,
                subscriptionLessonCount, subscriptionTotalRubles, useSubscriptionForSeries, null);
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration,
                         LessonRecurrence recurrence, Integer priceRubles, LessonPaymentMode paymentMode,
                         Integer subscriptionLessonCount, Integer subscriptionTotalRubles,
                         boolean useSubscriptionForSeries, Integer weeklyLessonCount) {
        requireTeacher(teacher);
        LessonPaymentMode mode = paymentMode == null ? LessonPaymentMode.SINGLE : paymentMode;
        if (!subscriptions.isEnabled() && mode != LessonPaymentMode.SINGLE)
            throw new IllegalArgumentException("Абонементы временно недоступны");
        if (recurrence == LessonRecurrence.ONCE && weeklyLessonCount != null && weeklyLessonCount != 1)
            throw new IllegalArgumentException("Для разового занятия количество в календаре должно быть равно одному");
        validatePrice(mode == LessonPaymentMode.SINGLE ? priceRubles : null);
        User student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Ученик не найден"));
        if (student.getRole() != Role.STUDENT || !connections.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.ACCEPTED))
            throw new IllegalArgumentException("Сначала примите ученика");
        Instant start = localStart.atZone(zone).toInstant();
        LessonSubscription createdSubscription = null;
        if (mode == LessonPaymentMode.CREATE_SUBSCRIPTION) {
            if (subscriptionLessonCount == null || subscriptionTotalRubles == null)
                throw new IllegalArgumentException("Укажите количество занятий и стоимость абонемента");
            createdSubscription = subscriptions.create(teacher, studentId, subscriptionLessonCount, subscriptionTotalRubles);
        }
        if (recurrence == LessonRecurrence.WEEKLY) {
            int calendarCount = resolveWeeklyLessonCount(mode, createdSubscription, teacher, student, weeklyLessonCount);
            Integer fallbackPrice = fallbackSeriesPrice(mode, priceRubles, createdSubscription, teacher, student, calendarCount);
            LessonSeries series = new LessonSeries(teacher, student, start, duration,
                    fallbackPrice);
            series.setUseSubscriptionByDefault(mode != LessonPaymentMode.SINGLE && useSubscriptionForSeries);
            series.limitToOccurrences(calendarCount);
            series = seriesRepository.save(series);
            List<Lesson> initialLessons = new ArrayList<>();
            for (int index = 0; index < calendarCount; index++) initialLessons.add(new Lesson(series, index));
            lessons.saveAllAndFlush(initialLessons);
            Lesson lesson = initialLessons.getFirst();
            if (mode == LessonPaymentMode.USE_SUBSCRIPTION
                    && subscriptions.allocateNearestAvailable(teacher, student, calendarCount) == 0)
                throw new IllegalArgumentException("В активных абонементах нет свободных занятий");
            if (createdSubscription != null)
                subscriptions.allocateNearest(teacher, createdSubscription, createdSubscription.getTotalLessons());
            notifications.seriesCreated(lesson, calendarCount);
            return lesson;
        }
        Lesson lesson = lessons.saveAndFlush(new Lesson(teacher, student, start, duration,
                mode == LessonPaymentMode.SINGLE ? priceRubles : null));
        if (mode == LessonPaymentMode.USE_SUBSCRIPTION && !subscriptions.attachOldestAvailable(teacher, lesson))
            throw new IllegalArgumentException("В активных абонементах нет свободных занятий");
        if (createdSubscription != null)
            subscriptions.allocateNearest(teacher, createdSubscription, createdSubscription.getTotalLessons());
        notifications.lessonCreated(lesson);
        return lesson;
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration) {
        return create(teacher, studentId, localStart, duration, LessonRecurrence.ONCE);
    }

    @Transactional
    public void reschedule(User teacher, Long id, LocalDateTime localStart, int duration, LessonChangeScope scope) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        if (lesson.getStatus() == LessonStatus.CANCELLED) throw new IllegalArgumentException("Отменённое занятие нельзя перенести");
        Instant oldStart = lesson.getStartAt();
        int oldDuration = lesson.getDurationMinutes();
        Instant newStart = localStart.atZone(zone).toInstant();
        if (scope == LessonChangeScope.FOLLOWING) {
            requireRecurring(lesson);
            Instant seriesOccurrenceStart = lesson.getSeries().occurrenceStart(lesson.getOccurrenceIndex());
            lesson.getSeries().shiftFrom(seriesOccurrenceStart, newStart, duration);
            List<Lesson> following = lessons.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                    lesson.getSeries().getId(), lesson.getOccurrenceIndex());
            following.forEach(item -> notifications.cancelReminder(item.getId()));
            following.stream()
                    .filter(item -> item.getStatus() == LessonStatus.SCHEDULED)
                    .forEach(item -> item.reschedule(
                            lesson.getSeries().occurrenceStart(item.getOccurrenceIndex()), duration));
            notifications.lessonRescheduled(lesson, oldStart, oldDuration, true);
            return;
        }
        lesson.reschedule(newStart, duration);
        notifications.lessonRescheduled(lesson, oldStart, oldDuration, false);
    }

    @Transactional
    public void reschedule(User teacher, Long id, LocalDateTime localStart, int duration) {
        reschedule(teacher, id, localStart, duration, LessonChangeScope.SINGLE);
    }

    @Transactional
    public void delete(User teacher, Long id, LessonChangeScope scope) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        if (scope == LessonChangeScope.FOLLOWING) {
            requireRecurring(lesson);
            notifications.lessonDeleted(lesson, true);
            lesson.getSeries().cancelFrom(lesson.getOccurrenceIndex());
            List<Lesson> following = lessons.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                    lesson.getSeries().getId(), lesson.getOccurrenceIndex());
            following.forEach(item -> notifications.cancelReminder(item.getId()));
            deleteLessons(following);
            return;
        }
        notifications.lessonDeleted(lesson, false);
        if (lesson.isRecurring()) lesson.getSeries().exclude(lesson.getOccurrenceIndex());
        deleteLessons(List.of(lesson));
    }

    @Transactional
    public void delete(User teacher, Long id) {
        delete(teacher, id, LessonChangeScope.SINGLE);
    }

    @Transactional
    public void deleteAsSubscriptionNoShow(User teacher, Long id) {
        Lesson lesson = subscriptions.markNoShow(teacher, id);
        deleteLessons(List.of(lesson), false);
    }

    @Transactional
    public DeletedLessons deleteForTeacherStudent(User teacher, User student) {
        requireTeacher(teacher);
        List<Lesson> items = lessons.findByTeacherAndStudentOrderByStartAtAsc(teacher, student);
        List<java.util.UUID> boardIds = whiteboards.publicIdsForLessons(items);
        deleteLessons(items, true);
        // A lesson references its series, therefore make sure the lesson rows are
        // gone before removing the now-unused series in the same transaction.
        lessons.flush();
        seriesRepository.deleteAll(seriesRepository.findByTeacherAndStudent(teacher, student));
        return new DeletedLessons(items.size(), boardIds);
    }
    @Transactional public void updateMaterials(User teacher, Long id, String homework, String notes) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        String normalizedHomework = blankToNull(homework);
        boolean homeworkChanged = !java.util.Objects.equals(lesson.getHomeworkText(), normalizedHomework);
        lesson.updateMaterials(normalizedHomework, blankToNull(notes));
        if (homeworkChanged) notifications.homeworkUpdated(lesson);
    }
    @Transactional public void updateMeetingUrl(User teacher, Long id, String meetingUrl) {
        requireTeacherLesson(teacher, id).updateMeetingUrl(normalizeMeetingUrl(meetingUrl));
    }

    @Transactional
    public void updateTeacherPrivateNote(User teacher, Long id, String note) {
        String normalized = blankToNull(note);
        if (normalized != null && normalized.length() > 10_000)
            throw new IllegalArgumentException("Личная заметка не должна превышать 10000 символов");
        requireTeacherLesson(teacher, id).updateTeacherPrivateNote(normalized);
    }

    @Transactional
    public Lesson updateHomeworkSubmissionStatus(User teacher, Long id, HomeworkSubmissionStatus status) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        lesson.updateHomeworkSubmissionStatus(status);
        return lesson;
    }

    private int resolveWeeklyLessonCount(LessonPaymentMode mode, LessonSubscription createdSubscription,
                                         User teacher, User student, Integer requested) {
        if (requested != null) {
            if (requested < 1 || requested > 104) throw new IllegalArgumentException("Укажите от 1 до 104 занятий в календаре");
            return requested;
        }
        if (mode == LessonPaymentMode.CREATE_SUBSCRIPTION) return createdSubscription.getTotalLessons();
        if (mode == LessonPaymentMode.USE_SUBSCRIPTION) {
            int available = subscriptions.availableCount(teacher, student);
            if (available < 1) throw new IllegalArgumentException("В активных абонементах нет свободных занятий");
            return Math.min(available, 104);
        }
        return 4;
    }

    private Integer fallbackSeriesPrice(LessonPaymentMode mode, Integer priceRubles,
                                        LessonSubscription createdSubscription, User teacher, User student,
                                        int calendarCount) {
        if (mode == LessonPaymentMode.SINGLE) return priceRubles;
        if (createdSubscription != null) {
            return createdSubscription.getTotalAmountRubles() / createdSubscription.getTotalLessons();
        }
        Integer creditPrice = subscriptions.fallbackCreditAmount(teacher, student, calendarCount);
        if (creditPrice != null) return creditPrice;
        return lessons.findFirstByTeacherAndStudentAndPriceRublesIsNotNullOrderByStartAtDescIdDesc(teacher, student)
                .map(Lesson::getPriceRubles).orElse(null);
    }

    @Transactional
    public PriceUpdateResult updatePrice(User teacher, Long id, Integer priceRubles,
                                         LessonChangeScope scope, boolean confirmPaidPriceChange) {
        validatePrice(priceRubles);
        Lesson lesson = requireTeacherLessonLocked(teacher, id);
        if (lesson.isPaidBySubscription())
            throw new IllegalArgumentException("Сначала верните занятие в абонемент");
        if (scope == LessonChangeScope.FOLLOWING) {
            requireRecurring(lesson);
            LessonSeries series = seriesRepository.findLockedById(lesson.getSeries().getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            series.changePriceFrom(lesson.getOccurrenceIndex(), priceRubles);
            List<Lesson> following = lessons.findFollowingForUpdate(
                    series.getId(), lesson.getOccurrenceIndex());
            int updated = 0;
            int skippedPaid = 0;
            for (Lesson item : following) {
                if (item.isPaidBySubscription()) {
                    skippedPaid++;
                    continue;
                }
                if (Objects.equals(item.getPriceRubles(), priceRubles)) continue;
                if (item.getPaymentStatus() == PaymentStatus.PAID) {
                    skippedPaid++;
                    continue;
                }
                item.updatePrice(priceRubles);
                updated++;
            }
            return new PriceUpdateResult(updated, skippedPaid);
        }
        if (!Objects.equals(lesson.getPriceRubles(), priceRubles)
                && lesson.getPaymentStatus() == PaymentStatus.PAID
                && !confirmPaidPriceChange) {
            throw new IllegalArgumentException(
                    "Стоимость оплаченного занятия изменится, а отметка об оплате будет сброшена. Подтвердите изменение.");
        }
        boolean changed = !Objects.equals(lesson.getPriceRubles(), priceRubles);
        lesson.updatePrice(priceRubles);
        return new PriceUpdateResult(changed ? 1 : 0, 0);
    }

    @Transactional
    public Lesson updatePaymentStatus(User teacher, Long id, PaymentStatus status) {
        return updatePaymentStatus(teacher, id, status, null).lesson();
    }

    @Transactional
    public PaymentStatusUpdate updatePaymentStatus(User teacher, Long id, PaymentStatus status,
                                                   Long expectedPaymentRecordId) {
        if (status == null || status == PaymentStatus.NO_PRICE)
            throw new IllegalArgumentException("Выберите корректный статус оплаты");
        Lesson lesson = requireTeacherLessonLocked(teacher, id);
        if (lesson.isPaidBySubscription())
            throw new IllegalArgumentException("Оплата абонементного занятия меняется в его карточке");
        if (expectedPaymentRecordId != null) {
            Long currentRecordId = paymentRecords.findByLessonId(id)
                    .map(LessonPaymentRecord::getId).orElse(null);
            if (!Objects.equals(currentRecordId, expectedPaymentRecordId))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Статус оплаты уже изменился в другой вкладке");
        }
        lesson.updatePaymentStatus(status);
        lessons.flush();
        Long recordId = paymentRecords.findByLessonId(id).map(LessonPaymentRecord::getId).orElse(null);
        return new PaymentStatusUpdate(lesson, recordId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> latestPrices(User teacher, List<User> students) {
        requireTeacher(teacher);
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (User student : students) {
            lessons.findFirstByTeacherAndStudentAndPriceRublesIsNotNullOrderByStartAtDescIdDesc(teacher, student)
                    .map(Lesson::getPriceRubles)
                    .ifPresent(price -> result.put(student.getId(), price));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Lesson requireAccessible(User user, Long id) {
        Lesson lesson = lessons.findWithStudentById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getRole() == Role.TEACHER && !lesson.getTeacher().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (user.getRole() == Role.STUDENT && !lesson.getStudent().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return lesson;
    }

    @Transactional(readOnly = true)
    public Lesson requireTeacherLesson(User teacher, Long id) {
        requireTeacher(teacher);
        Lesson lesson = lessons.findWithStudentById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!lesson.getTeacher().getId().equals(teacher.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return lesson;
    }

    @Transactional
    public List<Lesson> forMonth(User user, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        materializeBetween(user, from, to.minusNanos(1));
        return user.getRole() == Role.TEACHER
                ? lessons.findByTeacherAndStartAtBetweenOrderByStartAtAsc(user, from, to)
                : lessons.findByStudentAndStartAtBetweenOrderByStartAtAsc(user, from, to);
    }

    @Transactional
    public List<Lesson> upcoming(User user) {
        Instant now = clock.instant();
        materializeBetween(user, now, now.plus(365, ChronoUnit.DAYS));
        List<Lesson> result = user.getRole() == Role.TEACHER
                ? lessons.findByTeacherAndStartAtGreaterThanEqualOrderByStartAtAsc(user, now)
                : lessons.findByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(user, now);
        return result.stream().filter(l -> l.getStatus() == LessonStatus.SCHEDULED).toList();
    }

    private Lesson requireTeacherLessonLocked(User teacher, Long id) {
        requireTeacher(teacher);
        Lesson lesson = lessons.findLockedWithStudentById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!lesson.getTeacher().getId().equals(teacher.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return lesson;
    }

    @Transactional(readOnly = true)
    public List<Lesson> history(User student) {
        Instant now = clock.instant();
        return lessons.findByStudentOrderByStartAtDesc(student).stream()
                .filter(l -> l.getStatus() == LessonStatus.CANCELLED || l.isPast(now)).toList();
    }
    public boolean isPast(Lesson lesson) { return lesson.isPast(clock.instant()); }
    public boolean hasStarted(Lesson lesson) { return !lesson.getStartAt().isAfter(clock.instant()); }
    public ZoneId zone() { return zone; }
    private void materializeBetween(User user, Instant from, Instant until) {
        List<LessonSeries> relevantSeries = user.getRole() == Role.TEACHER
                ? seriesRepository.findByTeacher(user) : seriesRepository.findByStudent(user);
        materializeSeries(relevantSeries, from, until);
    }

    @Transactional
    public void materializeAllBetween(Instant from, Instant until) {
        materializeSeries(seriesRepository.findAll(), from, until);
    }

    @Transactional
    public void materializeForTeacherStudent(User teacher, User student, Instant from, Instant until) {
        requireTeacher(teacher);
        materializeSeries(seriesRepository.findByTeacherAndStudent(teacher, student), from, until);
    }

    @Transactional
    public void materializeForTeacher(User teacher, Instant from, Instant until) {
        requireTeacher(teacher);
        materializeSeries(seriesRepository.findByTeacher(teacher), from, until);
    }

    private void materializeSeries(List<LessonSeries> relevantSeries, Instant from, Instant until) {
        final long weekSeconds = Duration.ofDays(7).toSeconds();
        List<Lesson> generated = new ArrayList<>();
        for (LessonSeries series : relevantSeries) {
            if (series.getAnchorStartAt().isAfter(until)) continue;
            long secondsToFrom = Duration.between(series.getAnchorStartAt(), from).getSeconds();
            int firstIndex = secondsToFrom <= 0 ? 0 : (int) Math.min(
                    Math.ceilDiv(secondsToFrom, weekSeconds), 5_200);
            long secondsToEnd = Duration.between(series.getAnchorStartAt(), until).getSeconds();
            int lastIndex = (int) Math.min(Math.floorDiv(secondsToEnd, weekSeconds), 5_200);
            if (series.getCancelledFromIndex() != null) lastIndex = Math.min(lastIndex, series.getCancelledFromIndex() - 1);
            if (lastIndex < firstIndex) continue;
            var existing = new HashSet<>(lessons.findOccurrenceIndexesBySeriesId(series.getId()));
            for (int index = firstIndex; index <= lastIndex; index++) {
                if (series.includes(index) && !existing.contains(index)) generated.add(new Lesson(series, index));
            }
        }
        if (!generated.isEmpty()) {
            lessons.saveAllAndFlush(generated);
            generated.stream().filter(item -> item.getSeries().isUseSubscriptionByDefault())
                    .forEach(item -> subscriptions.attachOldestAvailable(item.getTeacher(), item));
        }
    }
    private void deleteLessons(List<Lesson> lessonsToDelete) {
        deleteLessons(lessonsToDelete, false);
    }

    private void deleteLessons(List<Lesson> lessonsToDelete, boolean preserveCompletedFinancialHistory) {
        for (Lesson item : lessonsToDelete) {
            if (!item.isPaidBySubscription()) continue;
            boolean completed = !item.getEndAt().isAfter(clock.instant());
            if (preserveCompletedFinancialHistory && completed) {
                paymentRecords.anonymizeLesson(item.getId());
            } else if (!item.getSubscriptionCredit().isConsumed()) {
                item.releaseSubscriptionCredit();
            }
        }
        lessons.flush();
        List<String> boardImages = whiteboards.storedImagesForLessons(lessonsToDelete);
        List<Attachment> attachmentsToDelete = new ArrayList<>();
        List<String> attachmentFiles = new ArrayList<>();
        for (Lesson item : lessonsToDelete) {
            notifications.cancelTransientForDeletedLesson(item.getId());
            List<Attachment> lessonAttachments = attachments.findByLessonOrderByCreatedAtAsc(item);
            attachmentsToDelete.addAll(lessonAttachments);
            for (Attachment attachment : lessonAttachments) {
                attachmentFiles.add(attachment.getStoredName());
            }
        }
        if (!attachmentsToDelete.isEmpty()) {
            attachments.deleteAll(attachmentsToDelete);
            attachments.flush();
        }
        // The database cascades a board's objects and images. Removing board rows
        // before lessons avoids Hibernate retaining a board for a deleted lesson.
        whiteboards.deleteBoardsForLessons(lessonsToDelete);
        lessons.deleteAll(lessonsToDelete);
        lessons.flush();
        deleteAttachmentFilesAfterCommit(attachmentFiles);
        whiteboards.deleteStoredImages(boardImages);
    }

    private void deleteAttachmentFilesAfterCommit(List<String> names) {
        if (names.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                names.forEach(name -> {
                    try {
                        Path file = storageRoot.resolve(name).normalize();
                        if (file.getParent().equals(storageRoot)) Files.deleteIfExists(file);
                    } catch (IOException ignored) { /* inaccessible files are never exposed and can be cleaned later */ }
                });
            }
        });
    }
    private void requireRecurring(Lesson lesson) {
        if (!lesson.isRecurring()) throw new IllegalArgumentException("Это занятие не входит в еженедельную серию");
    }
    private void validatePrice(Integer priceRubles) {
        if (priceRubles != null && (priceRubles < 1 || priceRubles > 1_000_000))
            throw new IllegalArgumentException("Стоимость должна быть от 1 до 1 000 000 ₽");
    }
    private String normalizeMeetingUrl(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) return null;
        try {
            URI url = new URI(trimmed);
            if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                    || url.getHost() == null || url.getUserInfo() != null) {
                throw new IllegalArgumentException("Укажите корректную ссылку на звонок, начиная с https://");
            }
            return url.toASCIIString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Укажите корректную ссылку на звонок, начиная с https://");
        }
    }
    private void requireTeacher(User user) { if (user.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    public record DeletedLessons(int lessonCount, List<java.util.UUID> boardIds) {}
    public record PaymentStatusUpdate(Lesson lesson, Long paymentRecordId) {}
    public record PriceUpdateResult(int updatedLessons, int skippedPaidLessons) {}
}
