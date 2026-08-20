package ru.repethelper.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.repethelper.service.LoginAttemptService;
import java.io.IOException;

public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final LoginAttemptService attempts;
    public LoginRateLimitFilter(LoginAttemptService attempts) { this.attempts = attempts; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())
                && !attempts.loginAllowed(request.getParameter("email"), request.getRemoteAddr())) {
            response.sendRedirect("/login?rate");
            return;
        }
        chain.doFilter(request, response);
    }
}
