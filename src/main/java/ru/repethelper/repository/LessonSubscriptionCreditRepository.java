package ru.repethelper.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.repethelper.domain.LessonSubscriptionCredit;
import ru.repethelper.domain.User;

import java.util.List;
import java.util.Optional;

public interface LessonSubscriptionCreditRepository extends JpaRepository<LessonSubscriptionCredit, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"subscription", "subscription.teacher", "subscription.student"})
    @Query("""
        select c from LessonSubscriptionCredit c
        where c.subscription.teacher = :teacher
          and c.subscription.student = :student
          and c.subscription.cancelledAt is null
          and c.consumedAt is null
          and not exists (select l.id from Lesson l where l.subscriptionCredit = c)
        order by c.subscription.createdAt, c.subscription.id, c.ordinal
        """)
    List<LessonSubscriptionCredit> findAvailableForUpdate(User teacher, User student);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"subscription", "subscription.teacher", "subscription.student"})
    @Query("select c from LessonSubscriptionCredit c where c.id = :id")
    Optional<LessonSubscriptionCredit> findLockedById(Long id);
}
