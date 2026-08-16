package ru.repethelper.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class LessonTest {
    private final User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
    private final User student = new User("student", "hash", "Ученик", Role.STUDENT);

    @Test void lessonBecomesPastAfterItsEnd() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        assertThat(lesson.isPast(Instant.parse("2026-07-23T10:59:59Z"))).isFalse();
        assertThat(lesson.isPast(Instant.parse("2026-07-23T11:00:00Z"))).isTrue();
    }

    @Test void cancelledLessonIsNotClassifiedAsPast() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        lesson.cancel();
        assertThat(lesson.isPast(Instant.parse("2026-07-24T10:00:00Z"))).isFalse();
        assertThat(lesson.getStatus()).isEqualTo(LessonStatus.CANCELLED);
    }

    @Test void meetingUrlCanBeAttachedAndRemoved() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        lesson.updateMeetingUrl("https://telemost.yandex.ru/j/123");
        assertThat(lesson.getMeetingUrl()).isEqualTo("https://telemost.yandex.ru/j/123");
        lesson.updateMeetingUrl(null);
        assertThat(lesson.getMeetingUrl()).isNull();
    }

    @Test void privateTeacherNoteIsIndependentFromSharedMaterials() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        lesson.updateMaterials("Домашняя работа", "Материалы для ученика");
        lesson.updateTeacherPrivateNote("Личная запись преподавателя");

        assertThat(lesson.getHomeworkText()).isEqualTo("Домашняя работа");
        assertThat(lesson.getLessonNotesText()).isEqualTo("Материалы для ученика");
        assertThat(lesson.getTeacherPrivateNote()).isEqualTo("Личная запись преподавателя");
    }

    @Test void homeworkSubmissionStartsUnmarkedAndCanBeChanged() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        assertThat(lesson.getHomeworkSubmissionStatus()).isEqualTo(HomeworkSubmissionStatus.NOT_MARKED);
        lesson.updateHomeworkSubmissionStatus(HomeworkSubmissionStatus.SUBMITTED);
        assertThat(lesson.getHomeworkSubmissionStatus()).isEqualTo(HomeworkSubmissionStatus.SUBMITTED);
        lesson.updateHomeworkSubmissionStatus(HomeworkSubmissionStatus.NOT_SUBMITTED);
        assertThat(lesson.getHomeworkSubmissionStatus()).isEqualTo(HomeworkSubmissionStatus.NOT_SUBMITTED);
    }

    @Test void pricedLessonStartsUnpaidAndCanBeMarkedPaid() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60, 1_500);

        assertThat(lesson.getPriceRubles()).isEqualTo(1_500);
        assertThat(lesson.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);

        lesson.updatePaymentStatus(PaymentStatus.PAID);
        assertThat(lesson.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test void changingPriceResetsPaymentAndRemovingItUsesNoPriceState() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60, 1_500);
        lesson.updatePaymentStatus(PaymentStatus.PAID);

        lesson.updatePrice(1_700);
        assertThat(lesson.getPriceRubles()).isEqualTo(1_700);
        assertThat(lesson.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);

        lesson.updatePrice(null);
        assertThat(lesson.getPriceRubles()).isNull();
        assertThat(lesson.getPaymentStatus()).isEqualTo(PaymentStatus.NO_PRICE);
    }

    @Test void lessonWithoutPriceCannotBeMarkedPaid() {
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lesson.updatePaymentStatus(PaymentStatus.PAID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("стоимость");
    }

    @Test void recurringLessonsUsePriceEffectiveForTheirOccurrence() {
        LessonSeries series = new LessonSeries(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60, 1_500);
        series.changePriceFrom(3, 1_700);
        series.changePriceFrom(7, null);

        assertThat(new Lesson(series, 2).getPriceRubles()).isEqualTo(1_500);
        assertThat(new Lesson(series, 3).getPriceRubles()).isEqualTo(1_700);
        assertThat(new Lesson(series, 6).getPriceRubles()).isEqualTo(1_700);
        assertThat(new Lesson(series, 7).getPriceRubles()).isNull();
        assertThat(new Lesson(series, 7).getPaymentStatus()).isEqualTo(PaymentStatus.NO_PRICE);
    }

    @Test void replacingSeriesPriceDropsRulesThatBelongToLaterFuture() {
        LessonSeries series = new LessonSeries(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60, 1_500);
        series.changePriceFrom(3, 1_700);
        series.changePriceFrom(7, 2_000);
        series.changePriceFrom(5, 1_800);

        assertThat(series.priceAt(4)).isEqualTo(1_700);
        assertThat(series.priceAt(5)).isEqualTo(1_800);
        assertThat(series.priceAt(8)).isEqualTo(1_800);
    }

    @Test void finiteSeriesIncludesExactlyItsPlannedOccurrences() {
        LessonSeries series = new LessonSeries(teacher, student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        series.limitToOccurrences(4);

        assertThat(series.includes(0)).isTrue();
        assertThat(series.includes(3)).isTrue();
        assertThat(series.includes(4)).isFalse();
    }
}
