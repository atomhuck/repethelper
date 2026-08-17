package ru.repethelper.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminGatewayFilter extends OncePerRequestFilter {
    public static final String GATEWAY_HEADER = "X-RepetHelper-Admin-Gateway";
    private final boolean enabled;
    private final String secret;

    public AdminGatewayFilter(@Value("${app.admin.enabled:false}") boolean enabled,
                              @Value("${app.admin.gateway-secret:}") String secret) {
        this.enabled = enabled;
        this.secret = secret == null ? "" : secret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/control");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader(GATEWAY_HEADER);
        if (!enabled || secret.length() < 32 || supplied == null
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
