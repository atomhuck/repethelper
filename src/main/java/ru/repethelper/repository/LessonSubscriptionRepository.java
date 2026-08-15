package ru.repethelper.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.repethelper.domain.LessonSubscription;
import ru.repethelper.domain.User;

import java.util.List;
import java.util.Optional;

public interface LessonSubscriptionRepository extends JpaRepository<LessonSubscription, Long> {
    @EntityGraph(attributePaths = {"teacher", "student", "credits"})
    List<LessonSubscription> findByTeacherAndStudentOrderByCreatedAtAscIdAsc(User teacher, User student);

    @EntityGraph(attributePaths = {"teacher", "student", "credits"})
    List<LessonSubscription> findByStudentOrderByCreatedAtAscIdAsc(User student);

    @EntityGraph(attributePaths = {"teacher", "student", "credits"})
    List<LessonSubscription> findByTeacherAndStudentInOrderByCreatedAtAscIdAsc(User teacher, List<User> students);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"teacher", "student"})
    @Query("select s from LessonSubscription s where s.id = :id")
    Optional<LessonSubscription> findLockedById(Long id);

    void deleteByTeacherAndStudent(User teacher, User student);
}
