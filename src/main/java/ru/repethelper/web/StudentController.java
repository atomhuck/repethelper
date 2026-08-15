package ru.repethelper.web;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.User;
import ru.repethelper.service.*;
import ru.repethelper.web.form.ConnectionForm;
import java.time.*;

@Controller
@RequestMapping("/student")
public class StudentController {
    private final AccountService accounts;
    private final ConnectionService connections;
    private final LessonService lessons;
    private final CalendarService calendars;
    private final LessonSubscriptionService subscriptions;
    public StudentController(AccountService accounts, ConnectionService connections, LessonService lessons,
                             CalendarService calendars, LessonSubscriptionService subscriptions) {
        this.accounts = accounts; this.connections = connections; this.lessons = lessons; this.calendars = calendars;
        this.subscriptions = subscriptions;
    }
    @GetMapping
    String dashboard(Authentication auth, @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month, Model model) {
        User student = current(auth);
        YearMonth selected = safeMonth(year, month);
        model.addAttribute("user", student);
        model.addAttribute("accepted", connections.isAccepted(student));
        var upcoming = lessons.upcoming(student);
        model.addAttribute("upcoming", upcoming);
        model.addAttribute("upcomingPreview", upcoming.stream().limit(4).toList());
        model.addAttribute("history", lessons.history(student));
        model.addAttribute("calendar", calendars.build(selected, lessons.forMonth(student, selected)));
        model.addAttribute("subscriptionSummaries", subscriptions.summariesForStudent(student));
        return "student/dashboard";
    }

    @GetMapping("/calendar")
    String calendarFragment(Authentication auth, @RequestParam(required = false) Integer year,
                            @RequestParam(required = false) Integer month, Model model, HttpServletResponse response) {
        User student = current(auth);
        YearMonth selected = safeMonth(year, month);
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("calendar", calendars.build(selected, lessons.forMonth(student, selected)));
        model.addAttribute("viewer", "student");
        return "calendar :: calendarPanel";
    }

    @GetMapping("/upcoming")
    String upcoming(Authentication auth, Model model) {
        User student = current(auth);
        model.addAttribute("user", student);
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
}
