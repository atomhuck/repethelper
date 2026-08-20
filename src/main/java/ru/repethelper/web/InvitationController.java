package ru.repethelper.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.ConnectionStatus;
import ru.repethelper.domain.Role;
import ru.repethelper.domain.User;
import ru.repethelper.security.RepetHelperPrincipal;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.ConnectionService;
import ru.repethelper.service.InvitationService;

@Controller
@RequestMapping("/invite")
public class InvitationController {
    private final InvitationService invitations;
    private final AccountService accounts;
    private final ConnectionService connections;

    public InvitationController(InvitationService invitations, AccountService accounts, ConnectionService connections) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.connections = connections;
    }

    @GetMapping("/{code}")
    String show(@PathVariable String code, Authentication authentication, HttpSession session, Model model) {
        InvitationService.InviteTarget target = invitations.requireActive(code);
        model.addAttribute("invite", target);
        User user = authenticatedUser(authentication);
        if (user == null) {
            invitations.remember(session, target);
            model.addAttribute("state", "GUEST");
        } else if (user.getRole() != Role.STUDENT) {
            invitations.clear(session);
            model.addAttribute("state", "NOT_STUDENT");
        } else {
            invitations.clear(session);
            model.addAttribute("state", connections.inviteState(user, target.teacherId()).name());
        }
        return "invite";
    }

    @PostMapping("/{code}/request")
    String request(@PathVariable String code, Authentication authentication, HttpSession session, RedirectAttributes flash) {
        InvitationService.InviteTarget target = invitations.requireActive(code);
        User student = authenticatedUser(authentication);
        if (student == null) {
            invitations.remember(session, target);
            return "redirect:/login";
        }
        try {
            connections.send(student, target.teacherId());
            invitations.clear(session);
            flash.addFlashAttribute("success", "Запрос отправлен преподавателю");
            return "redirect:/student";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            return "redirect:/invite/" + target.code();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unavailable(ResponseStatusException ignored) { return "invite-unavailable"; }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof RepetHelperPrincipal principal)) return null;
        return accounts.requireByUsername(principal.username());
    }
}
