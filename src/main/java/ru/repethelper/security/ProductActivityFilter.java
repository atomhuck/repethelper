package ru.repethelper.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.AdminConsoleService;

import java.io.IOException;

@Component
public class ProductActivityFilter extends OncePerRequestFilter {
    private final AccountService accounts;
    private final AdminConsoleService metrics;
    public ProductActivityFilter(AccountService accounts, AdminConsoleService metrics) { this.accounts = accounts; this.metrics = metrics; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/brand/") || path.startsWith("/fonts/")
                || path.startsWith("/vendor/") || path.startsWith("/actuator/") || path.startsWith("/control/") || path.startsWith("/ws/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof RepetHelperPrincipal principal) {
            metrics.recordActivity(accounts.requireByUsername(principal.username()), routeGroup(request.getRequestURI()));
        } else if ("GET".equalsIgnoreCase(request.getMethod())) {
            metrics.recordAnonymousVisit(request.getRemoteAddr(), request.getHeader("User-Agent"));
        }
        chain.doFilter(request, response);
    }

    private String routeGroup(String path) {
        if (path.startsWith("/teacher")) return "TEACHER";
        if (path.startsWith("/student")) return "STUDENT";
        if (path.startsWith("/lessons")) return "LESSON";
        if (path.startsWith("/boards")) return "BOARD";
        return "OTHER";
    }
}
