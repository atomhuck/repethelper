package ru.repethelper.web;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.User;
import ru.repethelper.domain.HomeworkSubmissionStatus;
import ru.repethelper.domain.PaymentStatus;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.LessonService;
import ru.repethelper.service.LessonSubscriptionService;
import ru.repethelper.service.TeacherStudentOverviewService;
import ru.repethelper.web.form.PrivateLessonNoteForm;
import ru.repethelper.web.form.StudentDescriptionForm;
import ru.repethelper.web.form.LessonPriceForm;

@Controller
@RequestMapping("/teacher")
public class TeacherStudentController {
    private final AccountService accounts;
    private final TeacherStudentOverviewService overviews;
    private final LessonService lessons;
    private final TextLinkifier textLinkifier;
    private final LessonSubscriptionService subscriptions;

    public TeacherStudentController(AccountService accounts, TeacherStudentOverviewService overviews,
                                    LessonService lessons, TextLinkifier textLinkifier,
                                    LessonSubscriptionService subscriptions) {
        this.accounts = accounts;
        this.overviews = overviews;
        this.lessons = lessons;
        this.textLinkifier = textLinkifier;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/students/{studentId}")
    String details(Authentication auth, @PathVariable Long studentId,
                   @RequestParam(defaultValue = "0") int upcomingPage,
                   @RequestParam(defaultValue = "0") int historyPage, Model model) {
        User teacher = current(auth);
        var overview = overviews.get(teacher, studentId, upcomingPage, historyPage);
        StudentDescriptionForm descriptionForm = new StudentDescriptionForm();
        descriptionForm.setDescription(overview.relation().getTeacherStudentDescription());

        model.addAttribute("user", teacher);
        model.addAttribute("overview", overview);
        model.addAttribute("student", overview.relation().getStudent());
        model.addAttribute("descriptionForm", descriptionForm);
        model.addAttribute("homeworkHtml", linkify(overview.nearest() == null ? null : overview.nearest().getHomeworkText()));
        model.addAttribute("materialsHtml", linkify(overview.previous() == null ? null : overview.previous().getLessonNotesText()));
        model.addAttribute("subscriptionEnabled", subscriptions.isEnabled());
        model.addAttribute("subscriptionSummary", subscriptions.summary(teacher, overview.relation().getStudent()));
        return "teacher/student-details";
    }

    @PostMapping("/students/{studentId}/description")
    String updateDescription(Authentication auth, @PathVariable Long studentId,
                             @Valid StudentDescriptionForm form, BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) {
            flash.addFlashAttribute("error", errors.getAllErrors().getFirst().getDefaultMessage());
            return "redirect:/teacher/students/" + studentId;
        }
        try {
            overviews.updateDescription(current(auth), studentId, form.getDescription());
            flash.addFlashAttribute("success", "Описание ученика сохранено");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/teacher/students/" + studentId;
    }

    @PostMapping("/lessons/{lessonId}/private-note")
    String updatePrivateNote(Authentication auth, @PathVariable Long lessonId,
                             @Valid PrivateLessonNoteForm form, BindingResult errors,
                             @RequestParam(defaultValue = "false") boolean returnToStudent,
                             RedirectAttributes flash) {
        if (errors.hasErrors()) {
            flash.addFlashAttribute("error", errors.getAllErrors().getFirst().getDefaultMessage());
        } else {
            try {
                lessons.updateTeacherPrivateNote(current(auth), lessonId, form.getNote());
                flash.addFlashAttribute("success", "Личная заметка сохранена");
            } catch (IllegalArgumentException ex) {
                flash.addFlashAttribute("error", ex.getMessage());
            }
        }
        return "redirect:/lessons/" + lessonId + (returnToStudent ? "?from=student" : "");
    }

    @PostMapping("/lessons/{lessonId}/homework-status")
    String updateHomeworkStatus(Authentication auth, @PathVariable Long lessonId,
                                @RequestParam HomeworkSubmissionStatus status,
                                @RequestParam(defaultValue = "false") boolean returnToStudentCard,
                                RedirectAttributes flash) {
        var lesson = lessons.updateHomeworkSubmissionStatus(current(auth), lessonId, status);
        flash.addFlashAttribute("success", switch (status) {
            case SUBMITTED -> "Отмечено: ученик сдал домашнюю работу";
            case NOT_SUBMITTED -> "Отмечено: ученик не сдал домашнюю работу";
            case NOT_MARKED -> "Отметка о домашней работе сброшена";
        });
        return returnToStudentCard
                ? "redirect:/teacher/students/" + lesson.getStudent().getId()
                : "redirect:/lessons/" + lessonId;
    }

    @PostMapping("/lessons/{lessonId}/price")
    String updatePrice(Authentication auth, @PathVariable Long lessonId,
                       @Valid LessonPriceForm form, BindingResult errors,
                       RedirectAttributes flash) {
        if (errors.hasErrors()) {
            flash.addFlashAttribute("error", errors.getAllErrors().getFirst().getDefaultMessage());
            return "redirect:/lessons/" + lessonId;
        }
        try {
            var result = lessons.updatePrice(current(auth), lessonId, form.getPriceRubles(),
                    form.getScope(), form.isConfirmPaidPriceChange());
            String message = form.getPriceRubles() == null ? "Стоимость занятия удалена" : "Стоимость занятия сохранена";
            if (result.skippedPaidLessons() > 0) {
                message += ". Оплаченных занятий без изменений: " + result.skippedPaidLessons();
            }
            flash.addFlashAttribute("success", message);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/lessons/" + lessonId;
    }

    @PostMapping("/lessons/{lessonId}/payment-status")
    String updatePaymentStatus(Authentication auth, @PathVariable Long lessonId,
                               @RequestParam PaymentStatus status,
                               @RequestParam(defaultValue = "false") boolean returnToStudentCard,
                               RedirectAttributes flash) {
        try {
            var lesson = lessons.updatePaymentStatus(current(auth), lessonId, status);
            flash.addFlashAttribute("success", status == PaymentStatus.PAID
                    ? "Занятие отмечено оплаченным"
                    : "Занятие отмечено неоплаченным");
            return returnToStudentCard
                    ? "redirect:/teacher/students/" + lesson.getStudent().getId()
                    : "redirect:/lessons/" + lessonId;
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            if (returnToStudentCard) {
                try {
                    var lesson = lessons.requireTeacherLesson(current(auth), lessonId);
                    return "redirect:/teacher/students/" + lesson.getStudent().getId();
                } catch (RuntimeException ignored) {
                    // Fall back to the lesson route, which will provide the correct 403/404.
                }
            }
            return "redirect:/lessons/" + lessonId;
        }
    }

    private String linkify(String value) {
        return value == null || value.isBlank() ? null : textLinkifier.linkify(value);
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
}
