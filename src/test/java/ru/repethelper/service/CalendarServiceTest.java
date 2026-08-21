package ru.repethelper.service;

import org.junit.jupiter.api.Test;
import ru.repethelper.domain.*;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CalendarServiceTest {
    @Test void buildsSixWeekMondayFirstCalendarAndPlacesLesson() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);
        CalendarService service = new CalendarService("Europe/Moscow", clock);
        User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
        User student = new User("student", "hash", "Ученик", Role.STUDENT);
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T14:00:00Z"), 60);

        var calendar = service.build(YearMonth.of(2026, 7), List.of(lesson));

        assertThat(calendar.days()).hasSize(42);
        assertThat(calendar.days().getFirst().date().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(calendar.days()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 7, 23)))
                .singleElement().satisfies(day -> {
                    assertThat(day.today()).isTrue();
                    assertThat(day.lessons()).containsExactly(lesson);
                    assertThat(day.hasScheduledLessons()).isTrue();
                });
    }

    @Test void cancelledLessonDoesNotMarkDayAsScheduled() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);
        CalendarService service = new CalendarService("Europe/Moscow", clock);
        User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
        User student = new User("student", "hash", "Ученик", Role.STUDENT);
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T14:00:00Z"), 60);
        lesson.cancel();

        var calendar = service.build(YearMonth.of(2026, 7), List.of(lesson));

        assertThat(calendar.days()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 7, 23)))
                .singleElement().satisfies(day -> assertThat(day.hasScheduledLessons()).isFalse());
    }

    @Test void marksCompletedLessonAsPastInsteadOfUpcoming() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);
        CalendarService service = new CalendarService("Europe/Moscow", clock);
        User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
        User student = new User("student", "hash", "Ученик", Role.STUDENT);
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-29T14:00:00Z"), 60);

        var calendar = service.build(YearMonth.of(2026, 7), List.of(lesson));

        assertThat(calendar.days()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 7, 29)))
                .singleElement().satisfies(day -> {
                    assertThat(day.hasPastLessons()).isTrue();
                    assertThat(day.hasUpcomingScheduledLessons()).isFalse();
                    assertThat(day.past(lesson)).isTrue();
                });
    }

    @Test void buildsMondayFirstWeekAndExpandsVisibleHours() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-19T09:30:00Z"), ZoneOffset.UTC);
        CalendarService service = new CalendarService("Europe/Moscow", clock);
        User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
        User student = new User("student", "hash", "Ученик", Role.STUDENT);
        Lesson early = new Lesson(teacher, student, Instant.parse("2026-08-17T03:30:00Z"), 60);
        Lesson late = new Lesson(teacher, student, Instant.parse("2026-08-21T19:15:00Z"), 90);

        var week = service.buildWeek(LocalDate.of(2026, 8, 19), List.of(early, late));

        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(week.end()).isEqualTo(LocalDate.of(2026, 8, 23));
        assertThat(week.days()).hasSize(7);
        assertThat(week.startHour()).isEqualTo(6);
        assertThat(week.endHour()).isEqualTo(24);
    }

    @Test void givesOverlappingLessonsSeparateStableColumns() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-17T06:00:00Z"), ZoneOffset.UTC);
        CalendarService service = new CalendarService("Europe/Moscow", clock);
        User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
        User student = new User("student", "hash", "Ученик", Role.STUDENT);
        Lesson first = new Lesson(teacher, student, Instant.parse("2026-08-17T07:00:00Z"), 90);
        Lesson second = new Lesson(teacher, student, Instant.parse("2026-08-17T07:30:00Z"), 60);
        Lesson third = new Lesson(teacher, student, Instant.parse("2026-08-17T09:00:00Z"), 60);

        var lessons = service.buildWeek(LocalDate.of(2026, 8, 17), List.of(first, second, third))
                .days().getFirst().lessons();

        assertThat(lessons).hasSize(3);
        assertThat(lessons.get(0).columns()).isEqualTo(2);
        assertThat(lessons.get(1).columns()).isEqualTo(2);
        assertThat(lessons.get(0).column()).isNotEqualTo(lessons.get(1).column());
        assertThat(lessons.get(2).columns()).isEqualTo(1);
    }
}
