package ru.repethelper.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.*;
import ru.repethelper.repository.AttachmentRepository;
import ru.repethelper.repository.ConnectionRequestRepository;
import ru.repethelper.repository.LessonRepository;
import ru.repethelper.repository.WhiteboardRepository;
import ru.repethelper.web.view.LearningProgressView;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TeacherStudentOverviewService {
    private static final int PAGE_SIZE = 20;
    private final ConnectionRequestRepository connections;
    private final LessonRepository lessons;
    private final AttachmentRepository attachments;
    private final LessonService lessonService;
    private final WhiteboardRepository whiteboards;
    private final Clock clock;

    public TeacherStudentOverviewService(ConnectionRequestRepository connections, LessonRepository lessons,
                                         AttachmentRepository attachments, LessonService lessonService,
                                         WhiteboardRepository whiteboards, Clock clock) {
        this.connections = connections;
        this.lessons = lessons;
        this.attachments = attachments;
        this.lessonService = lessonService;
        this.whiteboards = whiteboards;
        this.clock = clock;
    }

    @Transactional
    public Overview get(User teacher, Long studentId, int upcomingPage, int historyPage) {
        ConnectionRequest relation = requireAccepted(teacher, studentId);
        User student = relation.getStudent();
        Instant now = clock.instant();
        lessonService.materializeForTeacherStudent(teacher, student, now, now.plus(365, ChronoUnit.DAYS));

        Comparator<Lesson> ascending = Comparator.comparing(Lesson::getStartAt)
                .thenComparing(Lesson::getId);
        List<Lesson> all = new ArrayList<>(lessons.findByTeacherAndStudentOrderByStartAtAsc(teacher, student));
        all.sort(ascending);

        List<Lesson> upcoming = all.stream()
                .filter(item -> item.getStatus() == LessonStatus.SCHEDULED && item.getEndAt().isAfter(now))
                .toList();
        Lesson nearest = upcoming.stream().findFirst().orElse(null);
        Lesson previous = all.stream()
                .filter(item -> item.getStatus() != LessonStatus.CANCELLED && !item.getEndAt().isAfter(now))
                .max(Comparator.comparing(Lesson::getEndAt).thenComparing(Lesson::getId))
                .orElse(null);
        List<Lesson> history = all.stream()
                .filter(item -> item.getStatus() == LessonStatus.CANCELLED || !item.getEndAt().isAfter(now))
                .sorted(ascending.reversed())
                .toList();

        PageSelection upcomingSelection = selectPage(upcoming, upcomingPage);
        PageSelection historySelection = selectPage(history, historyPage);
        Set<Lesson> visibleLessons = new LinkedHashSet<>();
        if (nearest != null) visibleLessons.add(nearest);
        if (previous != null) visibleLessons.add(previous);
        visibleLessons.addAll(upcomingSelection.content());
        visibleLessons.addAll(historySelection.content());

        Map<Long, List<Attachment>> filesByLesson = visibleLessons.isEmpty()
                ? Map.of()
                : attachments.findByLessonInOrderByCreatedAtAsc(visibleLessons).stream()
                    .collect(Collectors.groupingBy(item -> item.getLesson().getId(), LinkedHashMap::new, Collectors.toList()));

        long boardCount = whiteboards.countVisibleForTeacherAndStudent(teacher, student, LessonStatus.CANCELLED);
        long completedLessons = all.stream()
                .filter(item -> item.getStatus() != LessonStatus.CANCELLED && !item.getEndAt().isAfter(now))
                .count();
        long submittedHomework = all.stream()
                .filter(item -> item.getHomeworkSubmissionStatus() == HomeworkSubmissionStatus.SUBMITTED)
                .count();
        LearningProgressView progress = new LearningProgressView(completedLessons, submittedHomework, boardCount,
                List.of(
                        new LearningProgressView.Milestone("Первое занятие", completedLessons >= 1),
                        new LearningProgressView.Milestone("10 проведённых занятий", completedLessons >= 10),
                        new LearningProgressView.Milestone("Первая выполненная домашняя работа", submittedHomework >= 1),
                        new LearningProgressView.Milestone("Первая совместная доска", boardCount >= 1)
                ));

        return new Overview(
                relation,
                nearest,
                previous,
                files(nearest, AttachmentCategory.HOMEWORK, filesByLesson),
                files(previous, AttachmentCategory.LESSON_NOTES, filesByLesson),
                toPage(upcomingSelection, filesByLesson),
                toPage(historySelection, filesByLesson),
                boardCount,
                progress,
                now
        );
    }

    @Transactional
    public void updateDescription(User teacher, Long studentId, String description) {
        ConnectionRequest relation = requireAccepted(teacher, studentId);
        String normalized = blankToNull(description);
        if (normalized != null && normalized.length() > 5_000)
            throw new IllegalArgumentException("Описание ученика не должно превышать 5000 символов");
        relation.updateTeacherStudentDescription(normalized);
    }

    @Transactional(readOnly = true)
    public ConnectionRequest requireAccepted(User teacher, Long studentId) {
        if (teacher.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return connections.findByStudentIdAndTeacherAndStatus(studentId, teacher, ConnectionStatus.ACCEPTED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private LessonPage toPage(PageSelection selection, Map<Long, List<Attachment>> filesByLesson) {
        List<LessonRow> rows = selection.content().stream()
                .map(lesson -> {
                    List<Attachment> files = filesByLesson.getOrDefault(lesson.getId(), List.of());
                    boolean homeworkFiles = files.stream().anyMatch(item -> item.getCategory() == AttachmentCategory.HOMEWORK);
                    boolean materialFiles = files.stream().anyMatch(item -> item.getCategory() == AttachmentCategory.LESSON_NOTES);
                    return new LessonRow(lesson,
                            hasText(lesson.getHomeworkText()) || homeworkFiles,
                            hasText(lesson.getLessonNotesText()) || materialFiles,
                            homeworkFiles || materialFiles,
                            hasText(lesson.getTeacherPrivateNote()));
                })
                .toList();
        return new LessonPage(rows, selection.page(), selection.totalPages(), selection.totalElements());
    }

    private List<Attachment> files(Lesson lesson, AttachmentCategory category,
                                   Map<Long, List<Attachment>> filesByLesson) {
        if (lesson == null) return List.of();
        return filesByLesson.getOrDefault(lesson.getId(), List.of()).stream()
                .filter(item -> item.getCategory() == category)
                .toList();
    }

    private PageSelection selectPage(List<Lesson> values, int requestedPage) {
        int totalPages = values.isEmpty() ? 0 : (values.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = totalPages == 0 ? 0 : Math.min(Math.max(0, requestedPage), totalPages - 1);
        int from = Math.min(page * PAGE_SIZE, values.size());
        int to = Math.min(from + PAGE_SIZE, values.size());
        return new PageSelection(values.subList(from, to), page, totalPages, values.size());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private record PageSelection(List<Lesson> content, int page, int totalPages, long totalElements) {}

    public record Overview(ConnectionRequest relation, Lesson nearest, Lesson previous,
                           List<Attachment> homeworkFiles, List<Attachment> materialFiles,
                           LessonPage upcoming, LessonPage history, long boardCount,
                           LearningProgressView progress, Instant now) {
        public boolean nearestInProgress() {
            return nearest != null && !nearest.getStartAt().isAfter(now) && nearest.getEndAt().isAfter(now);
        }
    }

    public record LessonRow(Lesson lesson, boolean hasHomework, boolean hasMaterials,
                            boolean hasFiles, boolean hasPrivateNote) {}

    public record LessonPage(List<LessonRow> content, int page, int totalPages, long totalElements) {
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page + 1 < totalPages; }
        public int previousPage() { return Math.max(0, page - 1); }
        public int nextPage() { return page + 1; }
    }
}
