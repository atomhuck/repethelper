package ru.repethelper.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.repethelper.service.AdminConsoleService;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

@Component
public class AdminSessionFilter extends OncePerRequestFilter {
    public static final String SESSION_KEY = "REPETHELPER_ADMIN_SESSION";
    private final AdminConsoleService admins;
    private final Clock clock;

    public AdminSessionFilter(AdminConsoleService admins, Clock clock) { this.admins = admins; this.clock = clock; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/control") || path.startsWith("/control/sign-in")
                || path.startsWith("/control/mfa") || path.startsWith("/control/bootstrap");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        AdminSession adminSession = session == null ? null : (AdminSession) session.getAttribute(SESSION_KEY);
        Instant now = clock.instant();
        if (adminSession == null || adminSession.expired(now) || !admins.sessionIsValid(adminSession)) {
            if (session != null) session.removeAttribute(SESSION_KEY);
            response.sendRedirect("/control/sign-in");
            return;
        }
        session.setAttribute(SESSION_KEY, adminSession.touch(now));
        chain.doFilter(request, response);
    }
}
