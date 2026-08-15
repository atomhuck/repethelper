package ru.repethelper.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.repethelper.domain.LessonPaymentRecord;

import java.util.Optional;

public interface LessonPaymentRecordRepository extends JpaRepository<LessonPaymentRecord, Long> {
    Optional<LessonPaymentRecord> findByLessonId(Long lessonId);

    @Modifying
    @Query("update LessonPaymentRecord r set r.lessonId = null where r.lessonId = :lessonId")
    int anonymizeLesson(Long lessonId);
}
