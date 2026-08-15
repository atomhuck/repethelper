package ru.repethelper.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.ConnectionRequest;
import ru.repethelper.domain.ConnectionStatus;
import ru.repethelper.domain.Lesson;
import ru.repethelper.domain.User;
import ru.repethelper.repository.AttachmentRepository;
import ru.repethelper.repository.ConnectionRequestRepository;
import ru.repethelper.repository.LessonRepository;
import ru.repethelper.repository.LessonSeriesRepository;
import ru.repethelper.repository.WhiteboardRepository;
import ru.repethelper.web.BoardRealtimeHub;

import java.util.List;

@Service
public class StudentRemovalService {
    private final ConnectionRequestRepository requests;
    private final LessonRepository lessons;
    private final LessonSeriesRepository series;
    private final AttachmentRepository attachments;
    private final WhiteboardRepository boards;
    private final LessonService lessonService;
    private final BoardRealtimeHub boardHub;
    private final LessonSubscriptionService subscriptions;

    public StudentRemovalService(ConnectionRequestRepository requests, LessonRepository lessons,
                                 LessonSeriesRepository series, AttachmentRepository attachments,
                                 WhiteboardRepository boards, LessonService lessonService, BoardRealtimeHub boardHub,
                                 LessonSubscriptionService subscriptions) {
        this.requests = requests; this.lessons = lessons; this.series = series; this.attachments = attachments;
        this.boards = boards; this.lessonService = lessonService; this.boardHub = boardHub;
        this.subscriptions = subscriptions;
    }

    @Transactional(readOnly = true)
    public RemovalPreview preview(User teacher, Long studentId) {
        ConnectionRequest relation = requireAccepted(teacher, studentId, false);
        List<Lesson> pairLessons = lessons.findByTeacherAndStudentOrderByStartAtAsc(teacher, relation.getStudent());
        return new RemovalPreview(relation.getStudent(), pairLessons.size(),
                series.findByTeacherAndStudent(teacher, relation.getStudent()).size(),
                attachments.countByLessonIn(pairLessons), boards.countByLessonIn(pairLessons));
    }

    @Transactional
    public RemovalPreview remove(User teacher, Long studentId) {
        ConnectionRequest relation = requireAccepted(teacher, studentId, true);
        User student = relation.getStudent();
        List<Lesson> pairLessons = lessons.findByTeacherAndStudentOrderByStartAtAsc(teacher, student);
        RemovalPreview summary = new RemovalPreview(student, pairLessons.size(),
                series.findByTeacherAndStudent(teacher, student).size(),
                attachments.countByLessonIn(pairLessons), boards.countByLessonIn(pairLessons));
        LessonService.DeletedLessons deleted = lessonService.deleteForTeacherStudent(teacher, student);
        subscriptions.deleteForPair(teacher, student);
        requests.deleteByStudentAndTeacher(student, teacher);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { boardHub.closeBoards(deleted.boardIds()); }
        });
        return summary;
    }

    private ConnectionRequest requireAccepted(User teacher, Long studentId, boolean lock) {
        List<ConnectionRequest> candidates = requests.findByTeacherAndStatusOrderByCreatedAtAsc(teacher, ConnectionStatus.ACCEPTED);
        ConnectionRequest found = candidates.stream().filter(item -> item.getStudent().getId().equals(studentId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!lock) return found;
        return requests.findLockedByStudentAndTeacherAndStatus(found.getStudent(), teacher, ConnectionStatus.ACCEPTED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record RemovalPreview(User student, int lessonCount, int seriesCount, long attachmentCount, long boardCount) {}
}
