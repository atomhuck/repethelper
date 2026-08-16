package ru.repethelper.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.User;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.LessonService;
import ru.repethelper.service.LessonSubscriptionService;
import ru.repethelper.web.MoneyInputParser;

@Controller
@RequestMapping("/teacher")
public class LessonSubscriptionController {
    private final AccountService accounts;
    private final LessonSubscriptionService subscriptions;
    private final LessonService lessons;
    private final MoneyInputParser money;

    public LessonSubscriptionController(AccountService accounts, LessonSubscriptionService subscriptions,
                                        LessonService lessons, MoneyInputParser money) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.lessons = lessons;
        this.money = money;
    }

    @PostMapping("/students/{studentId}/subscriptions")
    String create(Authentication auth, @PathVariable Long studentId,
                  @RequestParam int lessonCount, @RequestParam String totalRubles,
                  @RequestParam(defaultValue = "false") boolean applyToNearest,
                  RedirectAttributes flash) {
        try {
            User teacher = current(auth);
            var result = subscriptions.createAndAllocate(
                    teacher, studentId, lessonCount, money.parseRequired(totalRubles, "стоимость абонемента"), applyToNearest);
            int attached = result.attachedLessons();
            flash.addFlashAttribute("success", attached == 0
                    ? "Абонемент создан"
                    : "Абонемент создан · занятий запланировано: " + attached);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/teacher/students/" + studentId;
    }

    @PostMapping("/subscriptions/{id}/cancel-remaining")
    String cancel(Authentication auth, @PathVariable Long id, @RequestParam Long studentId,
                  RedirectAttributes flash) {
        try {
            subscriptions.cancelRemaining(current(auth), id);
            flash.addFlashAttribute("success", "Свободный остаток абонемента отменён");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/teacher/students/" + studentId;
    }

    @PostMapping("/subscriptions/{id}/delete")
    String delete(Authentication auth, @PathVariable Long id, @RequestParam Long studentId,
                  RedirectAttributes flash) {
        try {
            subscriptions.deleteEmpty(current(auth), id);
            flash.addFlashAttribute("success", "Пустой абонемент удалён");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/teacher/students/" + studentId;
    }

    @PostMapping("/lessons/{id}/subscription/attach")
    String attach(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        try {
            subscriptions.attach(current(auth), id);
            flash.addFlashAttribute("success", "Занятие оплачено абонементом");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/lessons/" + id;
    }

    @PostMapping("/lessons/{id}/subscription/release")
    String release(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        try {
            subscriptions.release(current(auth), id);
            flash.addFlashAttribute("success", "Занятие возвращено в баланс абонемента");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/lessons/" + id;
    }

    @PostMapping("/lessons/{id}/subscription/no-show")
    String noShow(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        try {
            lessons.deleteAsSubscriptionNoShow(current(auth), id);
            flash.addFlashAttribute("success", "Пропуск списан из абонемента");
            return "redirect:/teacher";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            return "redirect:/lessons/" + id;
        }
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
}
