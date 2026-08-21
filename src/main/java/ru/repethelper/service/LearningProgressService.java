package ru.repethelper.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repethelper.domain.*;
import ru.repethelper.repository.ConnectionRequestRepository;
import ru.repethelper.repository.WhiteboardRepository;
import ru.repethelper.web.view.LearningProgressView;

import java.util.List;

@Service
public class LearningProgressService {
    private final ConnectionRequestRepository connections;
    private final WhiteboardRepository whiteboards;

    public LearningProgressService(ConnectionRequestRepository connections, WhiteboardRepository whiteboards) {
        this.connections = connections;
        this.whiteboards = whiteboards;
    }

    @Transactional(readOnly = true)
    public StudentProgress forStudent(User student, List<Lesson> history) {
        long completed = history.stream().filter(lesson -> lesson.getStatus() != LessonStatus.CANCELLED).count();
        long submitted = history.stream()
                .filter(lesson -> lesson.getHomeworkSubmissionStatus() == HomeworkSubmissionStatus.SUBMITTED).count();
        long teachers = connections.findByStudentOrderByCreatedAtDesc(student).stream()
                .filter(connection -> connection.getStatus() == ConnectionStatus.ACCEPTED).count();
        long boards = whiteboards.countActiveBoardsForStudent(student);
        LearningProgressView learning = new LearningProgressView(completed, submitted, boards, List.of(
                new LearningProgressView.Milestone("Первое занятие", completed >= 1),
                new LearningProgressView.Milestone("10 проведённых занятий", completed >= 10),
                new LearningProgressView.Milestone("Первая выполненная домашняя работа", submitted >= 1),
                new LearningProgressView.Milestone("Первая совместная доска", boards >= 1)
        ));
        return new StudentProgress(learning, teachers);
    }

    public record StudentProgress(LearningProgressView learning, long activeTeachers) {}
}
