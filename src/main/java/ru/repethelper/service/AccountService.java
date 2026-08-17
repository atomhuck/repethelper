package ru.repethelper.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repethelper.domain.*;
import ru.repethelper.repository.TeacherProfileRepository;
import ru.repethelper.repository.UserRepository;
import ru.repethelper.security.RepetHelperPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AccountService implements UserDetailsService {
    public static final String TERMS_VERSION = "2026-07-24";
    public static final String PRIVACY_VERSION = "2026-07-24";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,40}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final UserRepository users;
    private final TeacherProfileRepository profiles;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public AccountService(UserRepository users, TeacherProfileRepository profiles, PasswordEncoder encoder) {
        this.users = users; this.profiles = profiles; this.encoder = encoder;
    }

    @Override @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = requireByIdentifier(identifier);
        if (!user.hasPassword()) throw new UsernameNotFoundException("Password sign-in is not configured");
        return RepetHelperPrincipal.from(user);
    }

    public RepetHelperPrincipal principalFor(User user) { return RepetHelperPrincipal.from(user); }

    @Transactional
    public User registerStudent(String displayName, String username, String password) {
        return register(displayName, username, null, password, Role.STUDENT, false);
    }

    @Transactional
    public User register(String displayName, String username, String email, String password,
                         Role role, boolean legalAccepted) {
        String normalizedUsername = normalize(username);
        String normalizedEmail = email == null || email.isBlank() ? null : normalize(email);
        if (displayName == null || displayName.trim().length() < 2 || displayName.trim().length() > 80)
            throw new IllegalArgumentException("Имя должно содержать от 2 до 80 символов");
        if (!USERNAME.matcher(normalizedUsername).matches())
            throw new IllegalArgumentException("Некорректный логин");
        if (normalizedEmail != null && (normalizedEmail.length() > 254 || !EMAIL.matcher(normalizedEmail).matches()))
            throw new IllegalArgumentException("Введите корректный email");
        if (role == null) throw new IllegalArgumentException("Выберите тип аккаунта");
        validatePassword(password);
        if (users.existsByUsernameIgnoreCase(normalizedUsername))
            throw new IllegalArgumentException("Логин уже занят");
        if (normalizedEmail != null && users.existsByEmailIgnoreCase(normalizedEmail))
            throw new IllegalArgumentException("Email уже используется");
        User user = new User(normalizedUsername, normalizedEmail, encoder.encode(password), displayName.trim(), role);
        if (legalAccepted) user.acceptLegal(TERMS_VERSION, PRIVACY_VERSION);
        user = users.save(user);
        if (role == Role.TEACHER) profiles.save(new TeacherProfile(user, generateInviteCode()));
        return user;
    }

    @Transactional
    public User registerFromExternalIdentity(String displayName, String username, String email,
                                             Role role, boolean emailVerified) {
        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email);
        validateRegistrationFields(displayName, normalizedUsername, normalizedEmail, role);
        User user = new User(normalizedUsername, normalizedEmail, null, displayName.trim(), role);
        user.acceptLegal(TERMS_VERSION, PRIVACY_VERSION);
        if (emailVerified) user.verifyEmail();
        user = users.save(user);
        if (role == Role.TEACHER) profiles.save(new TeacherProfile(user, generateInviteCode()));
        return user;
    }

    @Transactional(readOnly = true)
    public User requireByUsername(String username) {
        return users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
    }

    @Transactional(readOnly = true)
    public User requireByIdentifier(String identifier) {
        String normalized = normalize(identifier);
        return users.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
    }

    @Transactional
    public User completeLegacyProfile(User user, String email) {
        String normalizedEmail = normalize(email);
        if (normalizedEmail.length() > 254 || !EMAIL.matcher(normalizedEmail).matches())
            throw new IllegalArgumentException("Введите корректный email");
        users.findByEmailIgnoreCase(normalizedEmail).filter(found -> !found.getId().equals(user.getId()))
                .ifPresent(found -> { throw new IllegalArgumentException("Email уже используется"); });
        User managed = users.findById(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        managed.setEmail(normalizedEmail);
        managed.acceptLegal(TERMS_VERSION, PRIVACY_VERSION);
        return managed;
    }

    @Transactional(readOnly = true)
    public User requireByIdentifierOrNull(String identifier) {
        String normalized = normalize(identifier);
        return users.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean needsLegalAcceptance(User user) {
        return !user.hasAccepted(TERMS_VERSION, PRIVACY_VERSION);
    }

    @Transactional
    public void recordLoginFailure(String identifier, Instant now) {
        String normalized = normalize(identifier);
        users.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized)
                .ifPresent(user -> user.recordLoginFailure(now));
    }

    @Transactional
    public void clearLoginFailures(String username) {
        users.findByUsernameIgnoreCase(username).ifPresent(User::clearLoginFailures);
    }

    @Transactional(readOnly = true)
    public boolean isAccountLocked(String identifier, Instant now) {
        String normalized = normalize(identifier);
        return users.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized)
                .map(user -> user.isLocked(now)).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean matchesPassword(User user, String rawPassword) {
        return user.hasPassword() && rawPassword != null && encoder.matches(rawPassword, user.getPasswordHash());
    }

    @Transactional
    public void setPassword(User user, String password) {
        validatePassword(password);
        User managed = users.findById(user.getId()).orElseThrow();
        managed.changePassword(encoder.encode(password));
        managed.setMustChangePassword(false);
    }

    @Transactional
    public void invalidateSessions(User user) {
        users.findById(user.getId()).orElseThrow().invalidateSessions();
    }

    @Transactional
    public void recordLogin(User user) {
        users.findById(user.getId()).orElseThrow().recordLogin();
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 10
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Пароль должен содержать не менее 10 символов и не более 72 байт");
    }

    private String generateInviteCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder value = new StringBuilder("T-");
            for (int i = 0; i < 8; i++) value.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            String code = value.toString();
            if (profiles.findByInviteCodeIgnoreCase(code).isEmpty()) return code;
        }
        throw new IllegalStateException("Не удалось создать уникальный код приглашения");
    }

    private void validateRegistrationFields(String displayName, String normalizedUsername, String normalizedEmail,
                                            Role role) {
        if (displayName == null || displayName.trim().length() < 2 || displayName.trim().length() > 80)
            throw new IllegalArgumentException("Имя должно содержать от 2 до 80 символов");
        if (!USERNAME.matcher(normalizedUsername).matches())
            throw new IllegalArgumentException("Некорректный логин");
        if (normalizedEmail.length() > 254 || !EMAIL.matcher(normalizedEmail).matches())
            throw new IllegalArgumentException("Введите корректный email");
        if (role == null) throw new IllegalArgumentException("Выберите тип аккаунта");
        if (users.existsByUsernameIgnoreCase(normalizedUsername))
            throw new IllegalArgumentException("Логин уже занят");
        if (users.existsByEmailIgnoreCase(normalizedEmail))
            throw new IllegalArgumentException("Email уже используется");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
