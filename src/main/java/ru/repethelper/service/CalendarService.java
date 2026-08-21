package ru.repethelper.service;

import org.springframework.stereotype.Service;
import ru.repethelper.domain.Lesson;
import ru.repethelper.web.view.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CalendarService {
    private static final Locale RU = Locale.forLanguageTag("ru-RU");
    private final ZoneId zone;
    private final Clock clock;
    public CalendarService(@org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone, Clock clock) {
        this.zone = ZoneId.of(timezone); this.clock = clock;
    }
    public CalendarView build(YearMonth month, List<Lesson> lessons) {
        Map<LocalDate, List<Lesson>> byDate = lessons.stream().collect(Collectors.groupingBy(l -> l.getStartAt().atZone(zone).toLocalDate()));
        LocalDate first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();
        List<CalendarDay> days = new ArrayList<>(42);
        for (int i = 0; i < 42; i++) {
            LocalDate date = first.plusDays(i);
            days.add(new CalendarDay(date, YearMonth.from(date).equals(month), date.equals(today), byDate.getOrDefault(date, List.of()), now));
        }
        YearMonth prev = month.minusMonths(1), next = month.plusMonths(1);
        String rawTitle = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU));
        String title = rawTitle.substring(0, 1).toUpperCase(RU) + rawTitle.substring(1);
        return new CalendarView(month, title, prev.getYear(), prev.getMonthValue(), next.getYear(), next.getMonthValue(), days);
    }

    public WeekCalendarView buildWeek(LocalDate anchor, List<Lesson> lessons) {
        LocalDate start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();
        Map<LocalDate, List<Lesson>> byDate = lessons.stream()
                .collect(Collectors.groupingBy(lesson -> lesson.getStartAt().atZone(zone).toLocalDate()));

        int earliest = lessons.stream()
                .mapToInt(lesson -> lesson.getStartAt().atZone(zone).getHour())
                .min().orElse(8);
        int latest = lessons.stream()
                .mapToInt(lesson -> {
                    ZonedDateTime finish = lesson.getStartAt().plusSeconds(lesson.getDurationMinutes() * 60L).atZone(zone);
                    return finish.getHour() + (finish.getMinute() > 0 ? 1 : 0);
                })
                .max().orElse(21);
        int startHour = Math.max(0, Math.min(8, earliest));
        int endHour = Math.min(24, Math.max(21, latest));

        List<WeekDayView> days = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = start.plusDays(offset);
            List<Lesson> dayLessons = new ArrayList<>(byDate.getOrDefault(date, List.of()));
            dayLessons.sort(Comparator.comparing(Lesson::getStartAt)
                    .thenComparing(Lesson::getId, Comparator.nullsLast(Long::compareTo)));
            days.add(new WeekDayView(date, date.equals(today), layout(dayLessons, startHour, now)));
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMMM", RU);
        String title = start.format(formatter) + " — " + end.format(formatter);
        LocalDate selectedDate = anchor.isBefore(start) || anchor.isAfter(end) ? start : anchor;
        return new WeekCalendarView(start, end, start.minusWeeks(1), start.plusWeeks(1), today, selectedDate,
                title, startHour, endHour, days);
    }

    private List<WeekLessonView> layout(List<Lesson> lessons, int startHour, Instant now) {
        List<WeekLessonView> result = new ArrayList<>(lessons.size());
        int groupStart = 0;
        while (groupStart < lessons.size()) {
            int groupEnd = groupStart + 1;
            Instant maxEnd = endOf(lessons.get(groupStart));
            while (groupEnd < lessons.size() && lessons.get(groupEnd).getStartAt().isBefore(maxEnd)) {
                maxEnd = maxEnd.isAfter(endOf(lessons.get(groupEnd))) ? maxEnd : endOf(lessons.get(groupEnd));
                groupEnd++;
            }
            layoutGroup(lessons.subList(groupStart, groupEnd), startHour, now, result);
            groupStart = groupEnd;
        }
        result.sort(Comparator.comparing(view -> view.lesson().getStartAt()));
        return List.copyOf(result);
    }

    private void layoutGroup(List<Lesson> group, int startHour, Instant now, List<WeekLessonView> target) {
        List<Instant> columnEnds = new ArrayList<>();
        int[] assigned = new int[group.size()];
        for (int index = 0; index < group.size(); index++) {
            Lesson lesson = group.get(index);
            int column = 0;
            while (column < columnEnds.size() && columnEnds.get(column).isAfter(lesson.getStartAt())) column++;
            if (column == columnEnds.size()) columnEnds.add(endOf(lesson));
            else columnEnds.set(column, endOf(lesson));
            assigned[index] = column;
        }
        int columns = Math.max(1, columnEnds.size());
        for (int index = 0; index < group.size(); index++) {
            Lesson lesson = group.get(index);
            ZonedDateTime local = lesson.getStartAt().atZone(zone);
            int startMinute = (local.getHour() - startHour) * 60 + local.getMinute();
            Instant lessonEnd = endOf(lesson);
            target.add(new WeekLessonView(lesson, Math.max(0, startMinute), lesson.getDurationMinutes(),
                    assigned[index], columns, lessonEnd.isBefore(now) || lessonEnd.equals(now),
                    !lesson.getStartAt().isAfter(now) && lessonEnd.isAfter(now)));
        }
    }

    private Instant endOf(Lesson lesson) {
        return lesson.getStartAt().plusSeconds(lesson.getDurationMinutes() * 60L);
    }
}
