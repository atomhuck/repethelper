package ru.repethelper.web.view;

import ru.repethelper.domain.Lesson;

public record WeekLessonView(
        Lesson lesson,
        int startMinute,
        int durationMinutes,
        int column,
        int columns,
        boolean past,
        boolean current
) {}
