package ru.repethelper.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.repethelper.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface WhiteboardRepository extends JpaRepository<Whiteboard, Long> {
    Optional<Whiteboard> findByLesson(Lesson lesson);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    Optional<Whiteboard> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    @Query("select b from Whiteboard b where b.publicId = :publicId")
    Optional<Whiteboard> findLockedByPublicId(@Param("publicId") UUID publicId);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    List<Whiteboard> findByLessonIn(Collection<Lesson> lessons);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    @Query(value = "select b from Whiteboard b where b.lesson.teacher = :teacher and b.lesson.student = :student " +
            "and b.lesson.status <> :cancelled",
            countQuery = "select count(b) from Whiteboard b where b.lesson.teacher = :teacher and b.lesson.student = :student " +
                    "and b.lesson.status <> :cancelled")
    Page<Whiteboard> findVisibleForTeacherAndStudent(@Param("teacher") User teacher,
                                                      @Param("student") User student,
                                                      @Param("cancelled") LessonStatus cancelled,
                                                      Pageable pageable);

    @Query("select count(b) from Whiteboard b where b.lesson.teacher = :teacher and b.lesson.student = :student " +
            "and b.lesson.status <> :cancelled")
    long countVisibleForTeacherAndStudent(@Param("teacher") User teacher,
                                          @Param("student") User student,
                                          @Param("cancelled") LessonStatus cancelled);

    @Query("select b.publicId from Whiteboard b where b.lesson in :lessons")
    List<UUID> findPublicIdsByLessonIn(@Param("lessons") Collection<Lesson> lessons);

    @Modifying
    @Query("delete from Whiteboard b where b.lesson in :lessons")
    void deleteByLessonIn(@Param("lessons") Collection<Lesson> lessons);

    long countByLessonIn(Collection<Lesson> lessons);

    @Query("select count(b) from Whiteboard b where b.lesson.student = :student and " +
            "exists (select o.id from WhiteboardObject o where o.board = b and o.deletedAt is null)")
    long countActiveBoardsForStudent(@Param("student") User student);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    @Query("select b from Whiteboard b where b.lesson.teacher = :teacher and b.lesson.student = :student " +
            "and b.lesson.status <> :cancelled and exists (select o.id from WhiteboardObject o " +
            "where o.board = b and o.deletedAt is null) " +
            "order by b.lesson.startAt desc, b.id desc")
    List<Whiteboard> findRelatedActiveBoardsInitial(@Param("teacher") User teacher,
                                                     @Param("student") User student,
                                                     @Param("cancelled") LessonStatus cancelled,
                                                     Pageable pageable);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    @Query("select b from Whiteboard b where b.lesson.teacher = :teacher and b.lesson.student = :student " +
            "and b.lesson.status <> :cancelled and exists (select o.id from WhiteboardObject o " +
            "where o.board = b and o.deletedAt is null) and (b.lesson.startAt < :beforeStart " +
            "or (b.lesson.startAt = :beforeStart and b.id < :beforeId)) " +
            "order by b.lesson.startAt desc, b.id desc")
    List<Whiteboard> findRelatedActiveBoardsBefore(@Param("teacher") User teacher,
                                                    @Param("student") User student,
                                                    @Param("cancelled") LessonStatus cancelled,
                                                    @Param("beforeStart") java.time.Instant beforeStart,
                                                    @Param("beforeId") Long beforeId,
                                                    Pageable pageable);
}
