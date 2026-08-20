package ru.repethelper.config;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.repethelper.domain.Role;
import ru.repethelper.security.*;
import ru.repethelper.service.*;

@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountService accounts,
                                             LoginAttemptService attempts, InvitationService invitations,
                                             @Value("${app.account-gate-enabled:true}") boolean accountGateEnabled,
                                             AdminGatewayFilter adminGatewayFilter, AdminSessionFilter adminSessionFilter,
                                             ProductActivityFilter productActivityFilter) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/vendor/**", "/brand/**", "/fonts/**", "/login", "/register",
                        "/forgot-password", "/reset-password",
                        "/oauth2/**", "/login/oauth2/**", "/auth/vk/**",
                        "/join/**",
                        "/invite/**",
                        "/control/**",
                        "/legal/**", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/teacher/**").hasRole("TEACHER")
                .requestMatchers("/student/**").hasRole("STUDENT")
                .anyRequest().authenticated())
            .addFilterBefore(new LoginRateLimitFilter(attempts), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminGatewayFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(adminSessionFilter, AdminGatewayFilter.class)
            .addFilterAfter(new AccountStateFilter(accounts, accountGateEnabled), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(productActivityFilter, AccountStateFilter.class)
            .formLogin(form -> form.loginPage("/login").usernameParameter("email")
                .successHandler((request, response, authentication) -> {
                    attempts.loginSucceeded(authentication.getName());
                    var user = accounts.requireByUsername(authentication.getName());
                    accounts.recordLogin(user);
                    if (accounts.needsLegalAcceptance(user) || user.getEmail() == null) {
                        response.sendRedirect("/account/consent");
                    } else if (!user.isEmailVerified()) {
                        response.sendRedirect("/verify-email/pending");
                    } else {
                        boolean teacher = authentication.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_" + Role.TEACHER));
                        String invitation = invitations.takePendingPath(request.getSession(false), user.getRole())
                                .orElse(null);
                        response.sendRedirect(invitation != null ? invitation : (teacher ? "/teacher" : "/student"));
                    }
                })
                .failureHandler((request, response, exception) -> {
                    attempts.loginFailed(request.getParameter("email"), request.getRemoteAddr());
                    response.sendRedirect("/login?error");
                }).permitAll())
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) -> {
                var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                boolean signedIn = auth != null && auth.isAuthenticated()
                        && auth.getPrincipal() instanceof RepetHelperPrincipal;
                boolean teacher = signedIn && auth.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_" + Role.TEACHER));
                String path = request.getRequestURI();
                boolean wrongCabinet = (teacher && (path.equals("/student") || path.startsWith("/student/")))
                        || (!teacher && (path.equals("/teacher") || path.startsWith("/teacher/")));
                if (signedIn && "GET".equalsIgnoreCase(request.getMethod()) && wrongCabinet) {
                    response.sendRedirect(teacher ? "/teacher" : "/student");
                } else {
                    if (signedIn) {
                        log.warn("Access denied after authentication: method={}, path={}, role={}",
                                request.getMethod(), path, teacher ? "TEACHER" : "STUDENT");
                    }
                    response.sendError(403);
                }
            }))
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll());
        return http.build();
    }
}
