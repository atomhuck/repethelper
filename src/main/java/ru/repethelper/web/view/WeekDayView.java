package ru.repethelper.web.view;

import java.time.LocalDate;
import java.util.List;

public record WeekDayView(LocalDate date, boolean today, List<WeekLessonView> lessons) {}
