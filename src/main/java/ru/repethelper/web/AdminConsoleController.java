package ru.repethelper.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.Role;
import ru.repethelper.domain.User;
import ru.repethelper.security.AdminSession;
import ru.repethelper.security.AdminSessionFilter;
import ru.repethelper.service.AdminConsoleService;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/control")
public class AdminConsoleController {
    private static final String PENDING_LOGIN = "REPETHELPER_ADMIN_PENDING";
    private static final String BOOTSTRAP_RESULT = "REPETHELPER_ADMIN_BOOTSTRAP_RESULT";
    private final AdminConsoleService admins;

    public AdminConsoleController(AdminConsoleService admins) { this.admins = admins; }

    @GetMapping("/bootstrap")
    String bootstrap(Model model) {
        if (!admins.bootstrapAllowed()) return "redirect:/control/sign-in";
        model.addAttribute("bootstrapTokenRequired", admins.bootstrapTokenRequired());
        return "control/bootstrap";
    }

    @PostMapping("/bootstrap")
    String bootstrap(@RequestParam String token, @RequestParam String username, @RequestParam String password,
                     HttpServletRequest request, RedirectAttributes flash) {
        try {
            var result = admins.bootstrap(token, username, password, request.getRemoteAddr());
            request.getSession(true).setAttribute(BOOTSTRAP_RESULT, result);
            return "redirect:/control/bootstrap/complete";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage()); return "redirect:/control/bootstrap";
        }
    }

    @GetMapping("/bootstrap/complete")
    String bootstrapComplete(HttpSession session, Model model) {
        var result = (AdminConsoleService.BootstrapResult) session.getAttribute(BOOTSTRAP_RESULT);
        if (result == null) return "redirect:/control/sign-in";
        model.addAttribute("totpSecret", result.totpSecret()); model.addAttribute("recoveryCodes", result.recoveryCodes());
        return "control/bootstrap-complete";
    }

    @PostMapping("/bootstrap/complete")
    String finishBootstrap(HttpSession session) { session.removeAttribute(BOOTSTRAP_RESULT); return "redirect:/control/sign-in"; }

    @GetMapping("/sign-in")
    String signIn(HttpSession session) {
        if (session.getAttribute(AdminSessionFilter.SESSION_KEY) != null) return "redirect:/control";
        return "control/sign-in";
    }

    @PostMapping("/sign-in")
    String signIn(@RequestParam String username, @RequestParam String password, HttpServletRequest request,
                  RedirectAttributes flash) {
        var pending = admins.checkPassword(username, password, request.getRemoteAddr());
        if (pending.isEmpty()) { flash.addFlashAttribute("error", "Неверный логин, пароль или временная блокировка"); return "redirect:/control/sign-in"; }
        request.changeSessionId(); request.getSession(true).setAttribute(PENDING_LOGIN, pending.get());
        return "redirect:/control/mfa";
    }

    @GetMapping("/mfa")
    String mfa(HttpSession session) { return session.getAttribute(PENDING_LOGIN) == null ? "redirect:/control/sign-in" : "control/mfa"; }

    @PostMapping("/mfa")
    String mfa(@RequestParam String code, HttpServletRequest request, RedirectAttributes flash) {
        HttpSession session = request.getSession(false);
        var pending = session == null ? null : (AdminConsoleService.PendingLogin) session.getAttribute(PENDING_LOGIN);
        if (pending == null) return "redirect:/control/sign-in";
        var authenticated = code != null && code.matches("\\d{6}")
                ? admins.confirmTotp(pending.adminId(), code, request.getRemoteAddr())
                : admins.confirmRecoveryCode(pending.adminId(), code, request.getRemoteAddr());
        if (authenticated.isEmpty()) { flash.addFlashAttribute("error", "Неверный код из приложения-аутентификатора"); return "redirect:/control/mfa"; }
        session.removeAttribute(PENDING_LOGIN); session.setAttribute(AdminSessionFilter.SESSION_KEY, authenticated.get());
        return "redirect:/control";
    }

    @GetMapping("/reauth")
    String reauth() { return "control/reauth"; }

    @PostMapping("/reauth")
    String reauth(@RequestParam String code, HttpServletRequest request, RedirectAttributes flash) {
        AdminSession session = session(request);
        if (admins.confirmTotp(session.adminId(), code, request.getRemoteAddr()).isEmpty()) {
            flash.addFlashAttribute("error", "Неверный код"); return "redirect:/control/reauth";
        }
        request.getSession().setAttribute(AdminSessionFilter.SESSION_KEY, session.reconfirm(Instant.now()));
        return "redirect:/control";
    }

    @PostMapping("/sign-out")
    String signOut(HttpSession session) { session.invalidate(); return "redirect:/control/sign-in"; }

    @GetMapping
    String dashboard(HttpServletRequest request, Model model) {
        model.addAttribute("admin", admin(request)); model.addAttribute("dashboard", admins.dashboard()); return "control/dashboard";
    }

    @GetMapping("/users")
    String users(@RequestParam(required = false) String q, @RequestParam(required = false) Role role,
                 @RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
                 HttpServletRequest request, Model model) {
        model.addAttribute("admin", admin(request)); model.addAttribute("users", admins.users(q, role, state, page, 25));
        model.addAttribute("q", q); model.addAttribute("role", role); model.addAttribute("state", state); model.addAttribute("page", page);
        return "control/users";
    }

    @PostMapping("/users/create")
    String createUser(@RequestParam Role role, @RequestParam String displayName, @RequestParam String username,
                      @RequestParam String email, @RequestParam String reason, HttpServletRequest request,
                      RedirectAttributes flash) {
        if (!requireRecent(request, flash)) return "redirect:/control/reauth";
        try {
            var created = admins.createManualUser(admin(request).id(), role, displayName, username, email, request.getRemoteAddr(), reason);
            flash.addFlashAttribute("success", "Аккаунт создан. Временный пароль показан один раз.");
            flash.addFlashAttribute("temporaryPassword", created.temporaryPassword());
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users";
    }

    @PostMapping("/users/invitations")
    String inviteUser(@RequestParam Role role, @RequestParam String email, @RequestParam String reason,
                      HttpServletRequest request, RedirectAttributes flash) {
        if (!requireRecent(request, flash)) return "redirect:/control/reauth";
        try {
            var invitation = admins.createInvitation(admin(request).id(), role, email, request.getRemoteAddr(), reason);
            flash.addFlashAttribute("success", "Приглашение отправлено. Если почта задержится, ссылка показана ниже.");
            flash.addFlashAttribute("invitationLink", invitation.link());
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users";
    }

    @GetMapping("/users/{id}")
    String user(@PathVariable long id, HttpServletRequest request, Model model) {
        User target = admins.requireUser(id); model.addAttribute("admin", admin(request)); model.addAttribute("target", target);
        model.addAttribute("hasVk", admins.hasVk(target)); return "control/user";
    }

    @PostMapping("/users/{id}/profile")
    String profile(@PathVariable long id, @RequestParam String displayName, @RequestParam String username, @RequestParam String email,
                   @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        if (!requireRecent(request, flash)) return "redirect:/control/reauth";
        try { admins.editUser(admin(request).id(), id, displayName, username, email, request.getRemoteAddr(), reason); flash.addFlashAttribute("success", "Профиль обновлён, прежние сессии завершены"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users/" + id;
    }

    @PostMapping("/users/{id}/sessions/revoke")
    String revoke(@PathVariable long id, @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        try { admins.revokeUserSessions(admin(request).id(), id, request.getRemoteAddr(), reason); flash.addFlashAttribute("success", "Сессии пользователя завершены"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users/" + id;
    }

    @PostMapping("/users/{id}/block")
    String block(@PathVariable long id, @RequestParam String publicReason, @RequestParam(required = false) String internalNote,
                 @RequestParam(required = false) String endsAt, @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        if (!requireRecent(request, flash)) return "redirect:/control/reauth";
        try { admins.blockUser(admin(request).id(), id, publicReason, internalNote, parseInstant(endsAt), request.getRemoteAddr(), reason); flash.addFlashAttribute("success", "Аккаунт заблокирован"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users/" + id;
    }

    @PostMapping("/users/{id}/unblock")
    String unblock(@PathVariable long id, @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        try { admins.unblockUser(admin(request).id(), id, request.getRemoteAddr(), reason); flash.addFlashAttribute("success", "Блокировка снята"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users/" + id;
    }

    @PostMapping("/users/{id}/deletion")
    String delete(@PathVariable long id, @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        if (!requireRecent(request, flash)) return "redirect:/control/reauth";
        try { admins.scheduleDeletion(admin(request).id(), id, request.getRemoteAddr(), reason); flash.addFlashAttribute("success", "Удаление запланировано через 30 дней"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users/" + id;
    }

    @PostMapping("/users/{id}/deletion/cancel")
    String cancelDeletion(@PathVariable long id, @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        try { admins.cancelDeletion(admin(request).id(), id, request.getRemoteAddr(), reason); flash.addFlashAttribute("success", "Удаление отменено"); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/control/users/" + id;
    }

    @PostMapping("/users/{id}/support-access")
    String support(@PathVariable long id, @RequestParam String reason, HttpServletRequest request, RedirectAttributes flash) {
        try { UUID grant = admins.grantSupport(admin(request).id(), id, reason, request.getRemoteAddr()); return "redirect:/control/support/" + grant + "?user=" + id; }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); return "redirect:/control/users/" + id; }
    }

    @GetMapping("/support/{grant}")
    String support(@PathVariable UUID grant, @RequestParam(required = false) Long user, HttpServletRequest request, Model model) {
        AdminConsoleService.AdminAccountView admin = admin(request); long targetId = user == null ? -1 : user;
        if (targetId < 0 || !admins.supportGrantValid(grant, admin.id(), targetId)) return "error";
        model.addAttribute("admin", admin); model.addAttribute("target", admins.requireUser(targetId)); model.addAttribute("grant", grant);
        return "control/support";
    }

    private AdminConsoleService.AdminAccountView admin(HttpServletRequest request) {
        return admins.admin(session(request).adminId()).orElseThrow();
    }
    private AdminSession session(HttpServletRequest request) { return (AdminSession) request.getSession().getAttribute(AdminSessionFilter.SESSION_KEY); }
    private boolean requireRecent(HttpServletRequest request, RedirectAttributes flash) { if (session(request).recentlyConfirmed(Instant.now())) return true; flash.addFlashAttribute("error", "Подтвердите код TOTP перед чувствительной операцией"); return false; }
    private Instant parseInstant(String value) { if (value == null || value.isBlank()) return null; try { return Instant.parse(value); } catch (DateTimeParseException ex) { throw new IllegalArgumentException("Некорректная дата окончания блокировки"); } }
}
