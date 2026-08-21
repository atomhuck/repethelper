package ru.repethelper.web.view;

import java.time.LocalDate;
import java.util.List;

public record WeekCalendarView(
        LocalDate start,
        LocalDate end,
        LocalDate previousStart,
        LocalDate nextStart,
        LocalDate today,
        LocalDate selectedDate,
        String title,
        int startHour,
        int endHour,
        List<WeekDayView> days
) {
    public int visibleMinutes() {
        return (endHour - startHour) * 60;
    }
}
