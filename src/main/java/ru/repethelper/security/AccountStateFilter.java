package ru.repethelper.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.repethelper.service.AccountService;
import java.io.IOException;

public class AccountStateFilter extends OncePerRequestFilter {
    private final AccountService accounts;
    private final boolean enabled;
    public AccountStateFilter(AccountService accounts, boolean enabled) { this.accounts = accounts; this.enabled = enabled; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (!enabled) { chain.doFilter(request, response); return; }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof RepetHelperPrincipal principal) {
            var user = accounts.requireByUsername(principal.username());
            if (user.getAuthVersion() != principal.authVersion()) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.sendRedirect("/login?passwordChanged");
                return;
            }
            String path = request.getRequestURI();
            if (!isAccountPath(path)) {
                if (accounts.needsLegalAcceptance(user) || user.getEmail() == null) {
                    response.sendRedirect("/account/consent");
                    return;
                }
                if (!user.isEmailVerified()) {
                    response.sendRedirect("/verify-email/pending");
                    return;
                }
                if (user.isMustChangePassword()) {
                    response.sendRedirect("/account/change-password");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isAccountPath(String path) {
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/vendor/")
                || path.startsWith("/legal/") || path.equals("/account/consent")
                || path.startsWith("/verify-email") || path.equals("/forgot-password")
                || path.equals("/reset-password") || path.equals("/login")
                || path.equals("/register") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/")
                || path.startsWith("/auth/vk/") || path.equals("/account/security")
                || path.equals("/account/change-password")
                || path.equals("/error") || path.equals("/logout");
    }
}
