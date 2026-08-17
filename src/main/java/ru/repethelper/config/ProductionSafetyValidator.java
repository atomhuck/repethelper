package ru.repethelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionSafetyValidator implements ApplicationRunner {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,40}$");
    private static final Pattern TEACHER_CODE = Pattern.compile("^[\\p{L}\\p{N}_-]{8,30}$");

    private final String databasePassword;
    private final String teacherUsername;
    private final String teacherPassword;
    private final String teacherName;
    private final String teacherCode;
    private final String allowedOrigins;
    private final boolean adminEnabled;
    private final String adminGatewaySecret;
    private final String adminTotpEncryptionKey;
    private final boolean metricsEnabled;
    private final String metricsHmacKey;

    @Autowired
    public ProductionSafetyValidator(
            @Value("${spring.datasource.password:}") String databasePassword,
            @Value("${app.teacher.username:}") String teacherUsername,
            @Value("${app.teacher.password:}") String teacherPassword,
            @Value("${app.teacher.name:}") String teacherName,
            @Value("${app.teacher.code:}") String teacherCode,
            @Value("${app.websocket.allowed-origins:}") String allowedOrigins,
            @Value("${app.admin.enabled:false}") boolean adminEnabled,
            @Value("${app.admin.gateway-secret:}") String adminGatewaySecret,
            @Value("${app.admin.totp-encryption-key:}") String adminTotpEncryptionKey,
            @Value("${app.metrics.enabled:false}") boolean metricsEnabled,
            @Value("${app.metrics.hmac-key:}") String metricsHmacKey) {
        this.databasePassword = databasePassword;
        this.teacherUsername = teacherUsername;
        this.teacherPassword = teacherPassword;
        this.teacherName = teacherName;
        this.teacherCode = teacherCode;
        this.allowedOrigins = allowedOrigins;
        this.adminEnabled = adminEnabled;
        this.adminGatewaySecret = adminGatewaySecret;
        this.adminTotpEncryptionKey = adminTotpEncryptionKey;
        this.metricsEnabled = metricsEnabled;
        this.metricsHmacKey = metricsHmacKey;
    }

    // Keeps the small constructor used by configuration unit tests and local tooling stable.
    ProductionSafetyValidator(String databasePassword, String teacherUsername, String teacherPassword,
                              String teacherName, String teacherCode, String allowedOrigins) {
        this(databasePassword, teacherUsername, teacherPassword, teacherName, teacherCode, allowedOrigins,
                false, "", "", false, "");
    }

    @Override
    public void run(ApplicationArguments args) {
        requireSecret(databasePassword, "POSTGRES_PASSWORD");
        if (teacherPassword == null || teacherPassword.length() < 16 || teacherPassword.length() > 72
                || isPlaceholder(teacherPassword) || "change-me-now".equals(teacherPassword)) {
            throw unsafe("TEACHER_PASSWORD must contain 16-72 characters and not be a placeholder");
        }
        if (teacherUsername == null || !USERNAME.matcher(teacherUsername.trim()).matches()) {
            throw unsafe("TEACHER_USERNAME must contain 3-40 Latin letters, digits or . _ -");
        }
        if (teacherName == null || teacherName.trim().length() < 2 || teacherName.trim().length() > 80) {
            throw unsafe("TEACHER_NAME must contain 2-80 characters");
        }
        if (teacherCode == null || isPlaceholder(teacherCode) || "teacher_code".equals(teacherCode)
                || !TEACHER_CODE.matcher(teacherCode.trim()).matches()) {
            throw unsafe("TEACHER_CODE must contain 8-30 letters, digits, _ or -");
        }
        if (adminEnabled) {
            requireSecret(adminGatewaySecret, "APP_ADMIN_GATEWAY_SECRET");
            requireSecret(adminTotpEncryptionKey, "APP_ADMIN_TOTP_ENCRYPTION_KEY");
        }
        if (metricsEnabled) requireSecret(metricsHmacKey, "APP_METRICS_HMAC_KEY");

        String[] origins = allowedOrigins == null ? new String[0] : allowedOrigins.split(",");
        if (origins.length != 1) throw unsafe("exactly one WebSocket origin is required");
        URI origin;
        try {
            origin = URI.create(origins[0].trim());
        } catch (IllegalArgumentException ex) {
            throw unsafe("APP_WEBSOCKET_ALLOWED_ORIGINS is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                || origin.getHost().equalsIgnoreCase("localhost") || origin.getPort() != -1
                || origin.getHost().contains("your-domain") || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || (origin.getPath() != null && !origin.getPath().isEmpty())) {
            throw unsafe("APP_WEBSOCKET_ALLOWED_ORIGINS must be a single HTTPS site origin");
        }
    }

    private void requireSecret(String value, String name) {
        if (value == null || value.length() < 16 || isPlaceholder(value)) {
            throw unsafe(name + " must contain at least 16 characters and not be a placeholder");
        }
    }

    private boolean isPlaceholder(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("replace-");
    }

    private IllegalStateException unsafe(String message) {
        return new IllegalStateException("Unsafe production configuration: " + message);
    }
}
