package ru.repethelper.web;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.*;
import ru.repethelper.service.*;
import ru.repethelper.web.form.*;
import java.time.*;

@Controller
@RequestMapping("/teacher")
public class TeacherController {
    private final AccountService accounts;
    private final ConnectionService connections;
    private final TeacherProfileService profiles;
    private final LessonService lessons;
    private final CalendarService calendars;
    private final LessonSubscriptionService subscriptions;
    private final String baseUrl;
    public TeacherController(AccountService accounts, ConnectionService connections, TeacherProfileService profiles,
                             LessonService lessons, CalendarService calendars,
                             LessonSubscriptionService subscriptions,
                             @Value("${app.base-url}") String baseUrl) {
        this.accounts = accounts; this.connections = connections; this.profiles = profiles; this.lessons = lessons; this.calendars = calendars;
        this.subscriptions = subscriptions;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @GetMapping
    String dashboard(Authentication auth, @RequestParam(required = false) Integer year,
                     @RequestParam(required = false) Integer month,
                     @RequestParam(required = false) Long studentId, Model model) {
        User teacher = current(auth);
        YearMonth selected = safeMonth(year, month);
        var profile = profiles.requireFor(teacher);
        var profileForm = new TeacherProfileForm();
        profileForm.setDisplayName(teacher.getDisplayName());
        var students = connections.studentsFor(teacher);
        var lessonForm = new LessonForm();
        if (studentId != null && students.stream().anyMatch(student -> student.getId().equals(studentId))) {
            lessonForm.setStudentId(studentId);
            model.addAttribute("selectedStudentId", studentId);
        }
        model.addAttribute("user", teacher);
        model.addAttribute("profile", profile);
        model.addAttribute("profileForm", profileForm);
        model.addAttribute("inviteUrl", baseUrl + "/invite/" + profile.getInviteCode());
        model.addAttribute("lessonForm", lessonForm);
        model.addAttribute("pending", connections.pendingFor(teacher));
        model.addAttribute("students", students);
        model.addAttribute("latestPrices", lessons.latestPrices(teacher, students));
        model.addAttribute("subscriptionEnabled", subscriptions.isEnabled());
        model.addAttribute("subscriptionSummaries", subscriptions.summariesForStudents(teacher, students));
        var upcoming = lessons.upcoming(teacher);
        model.addAttribute("upcoming", upcoming);
        model.addAttribute("upcomingPreview", upcoming.stream().limit(4).toList());
        model.addAttribute("calendar", calendars.build(selected, lessons.forMonth(teacher, selected)));
        return "teacher/dashboard";
    }

    @GetMapping("/calendar")
    String calendarFragment(Authentication auth, @RequestParam(required = false) Integer year,
                            @RequestParam(required = false) Integer month, Model model, HttpServletResponse response) {
        User teacher = current(auth);
        YearMonth selected = safeMonth(year, month);
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("calendar", calendars.build(selected, lessons.forMonth(teacher, selected)));
        model.addAttribute("viewer", "teacher");
        return "calendar :: calendarPanel";
    }

    @GetMapping("/upcoming")
    String upcoming(Authentication auth, Model model) {
        User teacher = current(auth);
        model.addAttribute("user", teacher);
        model.addAttribute("upcoming", lessons.upcoming(teacher));
        return "teacher/upcoming";
    }

    @GetMapping("/students")
    String students(Authentication auth, Model model) {
        User teacher = current(auth);
        model.addAttribute("user", teacher);
        model.addAttribute("pending", connections.pendingFor(teacher));
        model.addAttribute("students", connections.studentsFor(teacher));
        return "teacher/students";
    }

    @PostMapping("/profile")
    String updateProfile(Authentication auth, @Valid TeacherProfileForm form, BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) return error(flash, errors.getAllErrors().getFirst().getDefaultMessage(), "/teacher");
        try { profiles.update(current(auth), form.getDisplayName()); flash.addFlashAttribute("success", "Профиль обновлён"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/teacher";
    }

    @PostMapping("/requests/{id}/accept")
    String accept(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        return processRequest(auth, id, true, flash);
    }
    @PostMapping("/requests/{id}/reject")
    String reject(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        return processRequest(auth, id, false, flash);
    }
    private String processRequest(Authentication auth, Long id, boolean accept, RedirectAttributes flash) {
        try { connections.process(current(auth), id, accept); flash.addFlashAttribute("success", accept ? "Ученик добавлен" : "Запрос отклонён"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/teacher/students";
    }

    @PostMapping("/lessons")
    String createLesson(Authentication auth, @Valid LessonForm form, BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) return error(flash, errors.getAllErrors().getFirst().getDefaultMessage(), "/teacher");
        try {
            Lesson lesson = lessons.create(current(auth), form.getStudentId(), form.getStartAt(),
                    form.getDurationMinutes(), form.getRecurrence(), form.getPriceRubles(), form.getPaymentMode(),
                    form.getSubscriptionLessonCount(), form.getSubscriptionTotalRubles(),
                    form.isUseSubscriptionForSeries(), form.getWeeklyLessonCount());
            flash.addFlashAttribute("success", form.getRecurrence() == LessonRecurrence.WEEKLY
                    ? "Еженедельные занятия добавлены в расписание"
                    : "Занятие добавлено в расписание");
            return "redirect:/lessons/" + lesson.getId();
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); return "redirect:/teacher"; }
    }

    @PostMapping("/lessons/{id}/reschedule")
    String reschedule(Authentication auth, @PathVariable Long id, @Valid LessonForm form, BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) return error(flash, errors.getAllErrors().getFirst().getDefaultMessage(), "/lessons/" + id);
        try {
            lessons.reschedule(current(auth), id, form.getStartAt(), form.getDurationMinutes(), form.getScope());
            flash.addFlashAttribute("success", form.getScope() == LessonChangeScope.FOLLOWING
                    ? "Это и все последующие занятия перенесены"
                    : "Занятие перенесено");
        }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/lessons/" + id;
    }

    @PostMapping("/lessons/{id}/delete")
    String delete(Authentication auth, @PathVariable Long id,
                  @RequestParam(defaultValue = "SINGLE") LessonChangeScope scope, RedirectAttributes flash) {
        try {
            lessons.delete(current(auth), id, scope);
            flash.addFlashAttribute("success", scope == LessonChangeScope.FOLLOWING
                    ? "Это и все последующие занятия удалены"
                    : "Занятие удалено");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/teacher";
    }

    @PostMapping("/lessons/{id}/materials")
    String materials(Authentication auth, @PathVariable Long id, @Valid LessonMaterialsForm form, BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) return error(flash, errors.getAllErrors().getFirst().getDefaultMessage(), "/lessons/" + id);
        lessons.updateMaterials(current(auth), id, form.getHomeworkText(), form.getLessonNotesText());
        flash.addFlashAttribute("success", "Материалы сохранены"); return "redirect:/lessons/" + id;
    }

    @PostMapping("/lessons/{id}/meeting-url")
    String updateMeetingUrl(Authentication auth, @PathVariable Long id, @Valid MeetingUrlForm form,
                            BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) return error(flash, errors.getAllErrors().getFirst().getDefaultMessage(), "/lessons/" + id);
        try {
            lessons.updateMeetingUrl(current(auth), id, form.getMeetingUrl());
            flash.addFlashAttribute("success", form.getMeetingUrl() == null || form.getMeetingUrl().isBlank()
                    ? "Ссылка на занятие удалена" : "Ссылка на занятие сохранена");
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/lessons/" + id;
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
    private YearMonth safeMonth(Integer year, Integer month) {
        if (year == null || month == null) return YearMonth.now(lessons.zone());
        try { return YearMonth.of(Math.max(2020, Math.min(2100, year)), month); }
        catch (DateTimeException ex) { return YearMonth.now(lessons.zone()); }
    }
    private String error(RedirectAttributes flash, String message, String target) { flash.addFlashAttribute("error", message); return "redirect:" + target; }
}
