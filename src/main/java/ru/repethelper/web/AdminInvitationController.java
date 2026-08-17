package ru.repethelper.web;

import jakarta.validation.Valid;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.User;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.AccountTokenService;
import ru.repethelper.service.AdminConsoleService;
import ru.repethelper.service.NotificationMailService;
import ru.repethelper.web.form.RegistrationForm;

@Controller
@RequestMapping("/join")
public class AdminInvitationController {
    private final AdminConsoleService admins;
    private final AccountService accounts;
    private final AccountTokenService tokens;
    private final NotificationMailService mail;
    public AdminInvitationController(AdminConsoleService admins, AccountService accounts, AccountTokenService tokens, NotificationMailService mail) {
        this.admins = admins; this.accounts = accounts; this.tokens = tokens; this.mail = mail;
    }
    @GetMapping("/{token}")
    String show(@PathVariable String token, Model model, RedirectAttributes flash) {
        try {
            var invitation = admins.invitation(token); RegistrationForm form = new RegistrationForm(); form.setEmail(invitation.email()); form.setRole(invitation.role());
            model.addAttribute("form", form); model.addAttribute("invitation", invitation); model.addAttribute("token", token); return "admin-invite-register";
        } catch (RuntimeException ex) { flash.addFlashAttribute("error", "Приглашение недействительно или уже истекло"); return "redirect:/login"; }
    }
    @PostMapping("/{token}")
    String join(@PathVariable String token, @Valid @ModelAttribute("form") RegistrationForm form, BindingResult errors,
                Model model, RedirectAttributes flash) {
        var invitation = admins.invitation(token);
        form.setEmail(invitation.email()); form.setRole(invitation.role());
        if (errors.hasErrors()) { model.addAttribute("invitation", invitation); model.addAttribute("token", token); return "admin-invite-register"; }
        try {
            User user = accounts.register(form.getDisplayName(), form.getUsername(), invitation.email(), form.getPassword(), invitation.role(), true);
            admins.consumeInvitation(token, invitation.id());
            try { mail.sendVerification(user.getEmail(), tokens.createVerification(user)); } catch (MailException ignored) { }
            flash.addFlashAttribute("success", "Аккаунт создан. Войдите и подтвердите email кодом из письма.");
            return "redirect:/login";
        } catch (IllegalArgumentException ex) { model.addAttribute("registrationError", ex.getMessage()); model.addAttribute("invitation", invitation); model.addAttribute("token", token); return "admin-invite-register"; }
    }
}
