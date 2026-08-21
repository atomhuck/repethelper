package ru.repethelper.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import ru.repethelper.domain.Lesson;
import ru.repethelper.domain.User;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    @EntityGraph(attributePaths = {"student", "teacher", "subscriptionCredit", "subscriptionCredit.subscription"})
    Optional<Lesson> findWithStudentById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"student", "teacher", "series"})
    @Query("select l from Lesson l where l.id = :id")
    Optional<Lesson> findLockedWithStudentById(Long id);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStartAtBetweenOrderByStartAtAsc(Instant from, Instant to);
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByTeacherAndStartAtBetweenOrderByStartAtAsc(User teacher, Instant from, Instant to);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStudentAndStartAtBetweenOrderByStartAtAsc(User student, Instant from, Instant to);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findTop8ByStartAtGreaterThanEqualOrderByStartAtAsc(Instant from);
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findTop8ByTeacherAndStartAtGreaterThanEqualOrderByStartAtAsc(User teacher, Instant from);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findTop8ByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(User student, Instant from);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByTeacherAndStartAtGreaterThanEqualOrderByStartAtAsc(User teacher, Instant from);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(User student, Instant from);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStudentOrderByStartAtDesc(User student);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByTeacherAndStudentOrderByStartAtAsc(User teacher, User student);

    @Query("select l from Lesson l where l.subscriptionCredit.id = :creditId")
    Optional<Lesson> findBySubscriptionCreditId(Long creditId);

    @Query("select l from Lesson l where l.subscriptionCredit.subscription.id = :subscriptionId order by l.startAt, l.id")
    List<Lesson> findBySubscriptionId(Long subscriptionId);

    @EntityGraph(attributePaths = {"subscriptionCredit", "subscriptionCredit.subscription"})
    @Query("select l from Lesson l where l.subscriptionCredit.subscription.id in :subscriptionIds order by l.startAt, l.id")
    List<Lesson> findBySubscriptionIds(List<Long> subscriptionIds);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(UUID seriesId, int occurrenceIndex);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"student", "teacher", "series"})
    @Query("select l from Lesson l where l.series.id = :seriesId and l.occurrenceIndex >= :occurrenceIndex order by l.occurrenceIndex")
    List<Lesson> findFollowingForUpdate(UUID seriesId, int occurrenceIndex);

    @EntityGraph(attributePaths = {"student", "teacher"})
    Optional<Lesson> findFirstByTeacherAndStudentAndPriceRublesIsNotNullOrderByStartAtDescIdDesc(User teacher, User student);

    @Query("select l.series.id, l.occurrenceIndex from Lesson l where l.series.id in :seriesIds")
    List<Object[]> findOccurrenceIndexesBySeriesIds(Collection<UUID> seriesIds);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStatusAndStartAtAfterAndStartAtLessThanEqualOrderByStartAtAsc(
            ru.repethelper.domain.LessonStatus status, Instant after, Instant until);
}
