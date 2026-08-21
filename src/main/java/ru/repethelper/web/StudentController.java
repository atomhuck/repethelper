package ru.repethelper.web;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.Lesson;
import ru.repethelper.domain.User;
import ru.repethelper.service.*;
import ru.repethelper.web.form.ConnectionForm;
import ru.repethelper.web.view.CalendarMode;
import java.time.*;
import java.util.List;

@Controller
@RequestMapping("/student")
public class StudentController {
    private final AccountService accounts;
    private final ConnectionService connections;
    private final LessonService lessons;
    private final CalendarService calendars;
    private final LessonSubscriptionService subscriptions;
    private final LearningProgressService progress;
    public StudentController(AccountService accounts, ConnectionService connections, LessonService lessons,
                             CalendarService calendars, LessonSubscriptionService subscriptions,
                             LearningProgressService progress) {
        this.accounts = accounts; this.connections = connections; this.lessons = lessons; this.calendars = calendars;
        this.subscriptions = subscriptions;
        this.progress = progress;
    }
    @GetMapping
    String dashboard(Authentication auth, @RequestParam(required = false) Integer year,
                     @RequestParam(required = false) Integer month,
                     @RequestParam(required = false) String view,
                     @RequestParam(required = false) String date, Model model) {
        User student = current(auth);
        YearMonth requestedMonth = year == null || month == null ? null : safeMonth(year, month);
        LocalDate selectedDate = safeDate(date, requestedMonth);
        YearMonth selected = requestedMonth == null ? YearMonth.from(selectedDate) : requestedMonth;
        CalendarMode calendarMode = calendarMode(view, year, month);
        model.addAttribute("user", student);
        model.addAttribute("viewer", "student");
        model.addAttribute("accepted", connections.isAccepted(student));
        var upcoming = lessons.upcoming(student);
        model.addAttribute("upcoming", upcoming);
        model.addAttribute("upcomingPreview", upcoming.stream().limit(4).toList());
        var history = lessons.history(student);
        model.addAttribute("history", history);
        model.addAttribute("progress", progress.forStudent(student, history));
        addCalendarModels(model, student, selected, selectedDate, calendarMode);
        model.addAttribute("subscriptionSummaries", subscriptions.summariesForStudent(student));
        return "student/dashboard";
    }

    @GetMapping("/calendar")
    String calendarFragment(Authentication auth, @RequestParam(required = false) Integer year,
                            @RequestParam(required = false) Integer month,
                            @RequestParam(required = false) String view,
                            @RequestParam(required = false) String date,
                            Model model, HttpServletResponse response) {
        User student = current(auth);
        YearMonth requestedMonth = year == null || month == null ? null : safeMonth(year, month);
        LocalDate selectedDate = safeDate(date, requestedMonth);
        YearMonth selected = requestedMonth == null ? YearMonth.from(selectedDate) : requestedMonth;
        CalendarMode calendarMode = calendarMode(view, year, month);
        response.setHeader("Cache-Control", "no-store");
        addCalendarModels(model, student, selected, selectedDate, calendarMode);
        model.addAttribute("viewer", "student");
        return "calendar :: calendarWorkspace";
    }

    @GetMapping("/upcoming")
    String upcoming(Authentication auth, Model model) {
        User student = current(auth);
        model.addAttribute("user", student);
        model.addAttribute("viewer", "student");
        model.addAttribute("upcoming", lessons.upcoming(student));
        return "student/upcoming";
    }

    @GetMapping("/teachers")
    String teachers(Authentication auth, Model model) {
        User student = current(auth);
        model.addAttribute("user", student);
        model.addAttribute("requests", connections.historyFor(student));
        model.addAttribute("connectionForm", new ConnectionForm());
        return "student/teachers";
    }

    @PostMapping("/requests")
    String request(Authentication auth, @Valid ConnectionForm form, BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) flash.addFlashAttribute("error", errors.getAllErrors().getFirst().getDefaultMessage());
        else try { connections.send(current(auth), form.getInviteCode()); flash.addFlashAttribute("success", "Запрос отправлен преподавателю"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/student/teachers";
    }
    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
    private YearMonth safeMonth(Integer year, Integer month) {
        if (year == null || month == null) return YearMonth.now(lessons.zone());
        try { return YearMonth.of(Math.max(2020, Math.min(2100, year)), month); }
        catch (DateTimeException ex) { return YearMonth.now(lessons.zone()); }
    }

    private CalendarMode calendarMode(String view, Integer year, Integer month) {
        if ((view == null || view.isBlank()) && year != null && month != null) {
            return CalendarMode.MONTH;
        }
        return CalendarMode.from(view);
    }
    private LocalDate safeDate(String value, YearMonth fallbackMonth) {
        if (value == null || value.isBlank()) {
            return fallbackMonth == null ? LocalDate.now(lessons.zone()) : fallbackMonth.atDay(1);
        }
        try { return LocalDate.parse(value); }
        catch (DateTimeException ex) { return fallbackMonth == null ? LocalDate.now(lessons.zone()) : fallbackMonth.atDay(1); }
    }
    private void addCalendarModels(Model model, User user, YearMonth month, LocalDate date, CalendarMode mode) {
        var monthLessons = lessons.forMonth(user, month);
        List<Lesson> weekLessons = mode == CalendarMode.WEEK ? lessons.forWeek(user, date) : List.of();
        model.addAttribute("calendar", calendars.build(month, monthLessons));
        model.addAttribute("weekCalendar", calendars.buildWeek(date, weekLessons));
        model.addAttribute("calendarMode", mode);
        model.addAttribute("selectedDate", date);
    }
}
