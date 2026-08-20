package ru.repethelper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.repethelper.domain.Role;
import ru.repethelper.domain.User;
import ru.repethelper.repository.ExternalIdentityRepository;
import ru.repethelper.repository.UserRepository;
import ru.repethelper.security.AdminSession;
import ru.repethelper.security.Totp;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class AdminConsoleService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DELETE_AFTER = Duration.ofDays(30);
    private final JdbcClient jdbc;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final AccountService accounts;
    private final AccountTokenService tokens;
    private final NotificationMailService mail;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final boolean enabled;
    private final String encryptionKey;
    private final String bootstrapToken;
    private final Path storageRoot;
    private final boolean metricsEnabled;
    private final String metricsHmacKey;
    private final String baseUrl;
    private final Map<String, Deque<Instant>> loginAttempts = new HashMap<>();

    public AdminConsoleService(JdbcClient jdbc, UserRepository users, ExternalIdentityRepository identities,
                               AccountService accounts, AccountTokenService tokens, NotificationMailService mail,
                               PasswordEncoder encoder, Clock clock,
                               @Value("${app.admin.enabled:false}") boolean enabled,
                               @Value("${app.admin.totp-encryption-key:}") String encryptionKey,
                               @Value("${app.admin.bootstrap-token:}") String bootstrapToken,
                               @Value("${app.storage-path:./uploads}") String storagePath,
                               @Value("${app.metrics.enabled:false}") boolean metricsEnabled,
                               @Value("${app.metrics.hmac-key:}") String metricsHmacKey,
                               @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.jdbc = jdbc; this.users = users; this.identities = identities; this.accounts = accounts; this.tokens = tokens; this.mail = mail;
        this.encoder = encoder; this.clock = clock; this.enabled = enabled;
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey;
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        this.metricsEnabled = metricsEnabled;
        this.metricsHmacKey = metricsHmacKey == null ? "" : metricsHmacKey;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    public boolean isEnabled() { return enabled; }
    /**
     * The initial setup page is already behind the private Tailscale gateway.
     * Requiring an operator to copy a server-side secret through another channel
     * made first setup needlessly fragile. A configured bootstrap token remains
     * supported for installations that want that extra ceremony, but it is not
     * required: the page closes permanently as soon as the first account exists.
     */
    public boolean bootstrapAllowed() { return enabled && adminCount() == 0; }
    public boolean bootstrapTokenRequired() { return !bootstrapToken.isBlank(); }
    public long adminCount() { return jdbc.sql("select count(*) from admin_accounts").query(Long.class).single(); }

    @Transactional
    public BootstrapResult bootstrap(String token, String username, String password, String sourceIp) {
        if (!bootstrapAllowed() || (bootstrapTokenRequired() && !safeEquals(bootstrapToken, token))) {
            throw new IllegalArgumentException("Настройка администратора недоступна");
        }
        validateAdmin(username, password);
        String secret = Totp.newSecret();
        Instant now = clock.instant();
        long id;
        try {
            id = jdbc.sql("""
                    insert into admin_accounts(username,password_hash,totp_secret_ciphertext,enabled,auth_version,created_at,updated_at)
                    values (:username,:password,:secret,true,0,:now,:now) returning id
                    """).param("username", username.trim().toLowerCase(Locale.ROOT)).param("password", encoder.encode(password))
                    .param("secret", encrypt(secret)).param("now", timestamp(now)).query(Long.class).single();
        } catch (DataIntegrityViolationException ex) { throw new IllegalArgumentException("Этот логин администратора уже занят"); }
        List<String> codes = createRecoveryCodes(id, now);
        audit(id, "ADMIN_BOOTSTRAPPED", "ADMIN", id, "Первичная настройка", sourceIp, Map.of());
        return new BootstrapResult(id, secret, codes);
    }

    public Optional<PendingLogin> checkPassword(String username, String password, String sourceIp) {
        if (!enabled || limited("login:" + sourceIp + ":" + normalized(username), Duration.ofMinutes(15), 5)) return Optional.empty();
        AdminAccount account = jdbc.sql("select id,username,password_hash,totp_secret_ciphertext,enabled,auth_version from admin_accounts where lower(username)=:username")
                .param("username", normalized(username)).query((rs, row) -> new AdminAccount(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("totp_secret_ciphertext"), rs.getBoolean("enabled"), rs.getLong("auth_version"))).optional().orElse(null);
        if (account == null || !account.enabled || password == null || !encoder.matches(password, account.passwordHash)) {
            markAttempt("login:" + sourceIp + ":" + normalized(username));
            if (account != null) audit(account.id, "ADMIN_LOGIN_FAILED", "ADMIN", account.id, null, sourceIp, Map.of());
            return Optional.empty();
        }
        return Optional.of(new PendingLogin(account.id, account.username));
    }

    @Transactional
    public Optional<AdminSession> confirmTotp(long id, String code, String sourceIp) {
        AdminAccount account = jdbc.sql("select id,username,password_hash,totp_secret_ciphertext,enabled,auth_version from admin_accounts where id=:id")
                .param("id", id).query((rs, row) -> new AdminAccount(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("totp_secret_ciphertext"), rs.getBoolean("enabled"), rs.getLong("auth_version"))).optional().orElse(null);
        if (account == null || !account.enabled || !Totp.matches(decrypt(account.totpSecret), code, clock.instant())) {
            if (account != null) audit(account.id, "ADMIN_MFA_FAILED", "ADMIN", account.id, null, sourceIp, Map.of());
            return Optional.empty();
        }
        Instant now = clock.instant();
        jdbc.sql("update admin_accounts set last_login_at=:now,updated_at=:now where id=:id").param("now", timestamp(now)).param("id", id).update();
        audit(id, "ADMIN_LOGIN_SUCCEEDED", "ADMIN", id, null, sourceIp, Map.of());
        return Optional.of(new AdminSession(id, now, now, now, account.authVersion));
    }

    @Transactional
    public Optional<AdminSession> confirmRecoveryCode(long id, String code, String sourceIp) {
        if (code == null || code.length() < 8 || code.length() > 32) return Optional.empty();
        AdminAccount account = jdbc.sql("select id,username,password_hash,totp_secret_ciphertext,enabled,auth_version from admin_accounts where id=:id")
                .param("id", id).query((rs, row) -> new AdminAccount(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("totp_secret_ciphertext"), rs.getBoolean("enabled"), rs.getLong("auth_version"))).optional().orElse(null);
        if (account == null || !account.enabled) return Optional.empty();
        var codes = jdbc.sql("select id,code_hash from admin_recovery_codes where admin_id=:admin and used_at is null order by id for update")
                .param("admin", id).query((rs, row) -> new RecoveryCode(rs.getLong("id"), rs.getString("code_hash"))).list();
        RecoveryCode matching = codes.stream().filter(item -> encoder.matches(code.trim().toUpperCase(Locale.ROOT), item.hash)).findFirst().orElse(null);
        if (matching == null) { audit(id, "ADMIN_RECOVERY_FAILED", "ADMIN", id, null, sourceIp, Map.of()); return Optional.empty(); }
        Instant now = clock.instant();
        jdbc.sql("update admin_recovery_codes set used_at=:now where id=:id and used_at is null").param("now", timestamp(now)).param("id", matching.id).update();
        jdbc.sql("update admin_accounts set last_login_at=:now,updated_at=:now where id=:id").param("now", timestamp(now)).param("id", id).update();
        audit(id, "ADMIN_RECOVERY_USED", "ADMIN", id, "Использован recovery-код", sourceIp, Map.of());
        return Optional.of(new AdminSession(id, now, now, now, account.authVersion));
    }

    public boolean sessionIsValid(AdminSession session) {
        return jdbc.sql("select enabled and auth_version=:version from admin_accounts where id=:id")
                .param("id", session.adminId()).param("version", session.authVersion()).query(Boolean.class).optional().orElse(false);
    }

    public Optional<AdminAccountView> admin(long id) {
        return jdbc.sql("select id,username,enabled,auth_version,last_login_at,created_at from admin_accounts where id=:id")
                .param("id", id).query((rs, row) -> new AdminAccountView(rs.getLong("id"), rs.getString("username"), rs.getBoolean("enabled"),
                        rs.getLong("auth_version"), rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant())).optional();
    }

    @Transactional
    public void editUser(long adminId, long userId, String displayName, String email, String sourceIp, String reason) {
        User user = requireUser(userId);
        String normalizedEmail = normalized(email);
        if (displayName == null || displayName.trim().length() < 2 || displayName.trim().length() > 80) throw new IllegalArgumentException("Имя: от 2 до 80 символов");
        if (!normalizedEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || normalizedEmail.length() > 254) throw new IllegalArgumentException("Некорректный email");
        users.findByEmailIgnoreCase(normalizedEmail).filter(found -> !found.getId().equals(userId)).ifPresent(found -> { throw new IllegalArgumentException("Email уже используется"); });
        Map<String, Object> before = Map.of("email", Objects.toString(user.getEmail(), ""));
        user.setDisplayName(displayName.trim()); user.setEmail(normalizedEmail); user.invalidateSessions();
        users.save(user);
        audit(adminId, "USER_PROFILE_UPDATED", "USER", userId, requiredReason(reason), sourceIp, before);
    }

    @Transactional
    public void blockUser(long adminId, long userId, String publicReason, String internalNote, Instant endsAt, String sourceIp, String reason) {
        User user = requireUser(userId); Instant now = clock.instant();
        if (publicReason == null || publicReason.isBlank() || publicReason.length() > 500) throw new IllegalArgumentException("Укажите публичную причину блокировки");
        if (endsAt != null && !endsAt.isAfter(now)) throw new IllegalArgumentException("Дата окончания должна быть в будущем");
        user.setEnabled(false); user.invalidateSessions(); users.save(user);
        jdbc.sql("insert into user_account_restrictions(user_id,admin_id,public_reason,internal_note,starts_at,ends_at) values (:user,:admin,:public,:internal,:now,:ends)")
                .param("user", userId).param("admin", adminId).param("public", publicReason.trim())
                .param("internal", blankToNull(internalNote)).param("now", timestamp(now)).param("ends", timestamp(endsAt)).update();
        audit(adminId, "USER_BLOCKED", "USER", userId, requiredReason(reason), sourceIp, Map.of("temporary", endsAt != null));
    }

    @Transactional
    public void unblockUser(long adminId, long userId, String sourceIp, String reason) {
        User user = requireUser(userId);
        jdbc.sql("update user_account_restrictions set lifted_at=:now,lifted_by_admin_id=:admin where user_id=:user and lifted_at is null")
                .param("now", timestamp(clock.instant())).param("admin", adminId).param("user", userId).update();
        if (user.getDeletionScheduledAt() == null) user.setEnabled(true);
        user.invalidateSessions(); users.save(user);
        audit(adminId, "USER_UNBLOCKED", "USER", userId, requiredReason(reason), sourceIp, Map.of());
    }

    @Transactional
    public void revokeUserSessions(long adminId, long userId, String sourceIp, String reason) {
        User user = requireUser(userId); user.invalidateSessions(); users.save(user);
        audit(adminId, "USER_SESSIONS_REVOKED", "USER", userId, requiredReason(reason), sourceIp, Map.of());
    }

    @Transactional
    public void scheduleDeletion(long adminId, long userId, String sourceIp, String reason) {
        User user = requireUser(userId); user.setEnabled(false); user.invalidateSessions(); user.scheduleDeletion(clock.instant().plus(DELETE_AFTER)); users.save(user);
        audit(adminId, "USER_DELETION_SCHEDULED", "USER", userId, requiredReason(reason), sourceIp, Map.of("days", 30));
    }

    @Transactional
    public void cancelDeletion(long adminId, long userId, String sourceIp, String reason) {
        User user = requireUser(userId); user.cancelDeletion();
        boolean restricted = jdbc.sql("select exists(select 1 from user_account_restrictions where user_id=:id and lifted_at is null and (ends_at is null or ends_at > :now))")
                .param("id", userId).param("now", timestamp(clock.instant())).query(Boolean.class).single();
        user.setEnabled(!restricted); user.invalidateSessions(); users.save(user);
        audit(adminId, "USER_DELETION_CANCELLED", "USER", userId, requiredReason(reason), sourceIp, Map.of());
    }

    @Transactional
    public UUID grantSupport(long adminId, long userId, String reason, String sourceIp) {
        requireUser(userId); String verifiedReason = requiredReason(reason); Instant now = clock.instant(); UUID id = UUID.randomUUID();
        jdbc.sql("insert into admin_support_grants(id,admin_id,user_id,reason,expires_at,created_at) values (:id,:admin,:user,:reason,:expires,:now)")
                .param("id", id).param("admin", adminId).param("user", userId).param("reason", verifiedReason)
                .param("expires", timestamp(now.plusSeconds(900))).param("now", timestamp(now)).update();
        audit(adminId, "SUPPORT_ACCESS_GRANTED", "USER", userId, verifiedReason, sourceIp, Map.of("minutes", 15));
        return id;
    }

    public boolean supportGrantValid(UUID grantId, long adminId, long userId) {
        return jdbc.sql("select exists(select 1 from admin_support_grants where id=:id and admin_id=:admin and user_id=:user and revoked_at is null and expires_at > :now)")
                .param("id", grantId).param("admin", adminId).param("user", userId).param("now", timestamp(clock.instant())).query(Boolean.class).single();
    }

    @Transactional
    public ManualUserResult createManualUser(long adminId, Role role, String displayName, String email,
                                             String sourceIp, String reason) {
        if (role == null) throw new IllegalArgumentException("Выберите роль");
        String temporaryPassword = randomTemporaryPassword();
        User user = accounts.registerGenerated(displayName, email, temporaryPassword, role, false);
        user.setMustChangePassword(true); users.save(user);
        try { mail.sendVerification(user.getEmail(), tokens.createVerification(user)); } catch (MailException ignored) { }
        audit(adminId, "USER_CREATED_MANUALLY", "USER", user.getId(), requiredReason(reason), sourceIp, Map.of("role", role.name()));
        return new ManualUserResult(user, temporaryPassword);
    }

    @Transactional
    public InvitationResult createInvitation(long adminId, Role role, String email, String sourceIp, String reason) {
        if (role == null) throw new IllegalArgumentException("Выберите роль");
        String normalizedEmail = normalized(email);
        if (!normalizedEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || normalizedEmail.length() > 254) throw new IllegalArgumentException("Некорректный email");
        if (users.existsByEmailIgnoreCase(normalizedEmail)) throw new IllegalArgumentException("Email уже используется");
        Instant now = clock.instant(); UUID id = UUID.randomUUID(); String token = randomInvitationToken();
        jdbc.sql("update admin_user_invitations set revoked_at=:now where lower(email)=:email and used_at is null and revoked_at is null")
                .param("now", timestamp(now)).param("email", normalizedEmail).update();
        jdbc.sql("insert into admin_user_invitations(id,email,role,token_hash,created_by_admin_id,created_at,expires_at) values (:id,:email,:role,:hash,:admin,:now,:expires)")
                .param("id", id).param("email", normalizedEmail).param("role", role.name()).param("hash", sha256(token))
                .param("admin", adminId).param("now", timestamp(now)).param("expires", timestamp(now.plus(Duration.ofHours(48)))).update();
        String link = baseUrl + "/join/" + token;
        try { mail.sendNotification(normalizedEmail, "Приглашение в RepetHelper", "Вас пригласили в RepetHelper как " + (role == Role.TEACHER ? "преподавателя" : "ученика") + ".\n\nЗавершите регистрацию в течение 48 часов:\n" + link); } catch (MailException ignored) { }
        audit(adminId, "USER_INVITATION_CREATED", "INVITATION", null, requiredReason(reason), sourceIp, Map.of("role", role.name()));
        return new InvitationResult(id, link, normalizedEmail, role);
    }

    public InvitationView invitation(String token) {
        if (token == null || token.length() < 20) throw new NoSuchElementException("Приглашение не найдено");
        return jdbc.sql("select id,email,role,expires_at from admin_user_invitations where token_hash=:hash and used_at is null and revoked_at is null and expires_at > :now")
                .param("hash", sha256(token)).param("now", timestamp(clock.instant())).query((rs, row) -> new InvitationView(
                        (UUID) rs.getObject("id"), rs.getString("email"), Role.valueOf(rs.getString("role")), rs.getTimestamp("expires_at").toInstant())).optional()
                .orElseThrow(() -> new NoSuchElementException("Приглашение не найдено или истекло"));
    }

    @Transactional
    public void consumeInvitation(String token, UUID invitationId) {
        int updated = jdbc.sql("update admin_user_invitations set used_at=:now where id=:id and token_hash=:hash and used_at is null and revoked_at is null and expires_at > :now")
                .param("now", timestamp(clock.instant())).param("id", invitationId).param("hash", sha256(token)).update();
        if (updated != 1) throw new IllegalArgumentException("Приглашение уже использовано или истекло");
    }

    public Dashboard dashboard() {
        Instant now = clock.instant();
        Instant today = LocalDate.now(ZoneId.of("Europe/Moscow")).atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant();
        return new Dashboard(count("select count(*) from app_users"), count("select count(*) from app_users where role='TEACHER'"),
                count("select count(*) from app_users where role='STUDENT'"), count("select count(*) from app_users where enabled"),
                count("select count(*) from app_users where not enabled"), count("select count(*) from app_users where deletion_scheduled_at is not null and deletion_completed_at is null"),
                count("select count(*) from app_users where created_at >= :from", Map.of("from", today)),
                count("select count(distinct user_id) from product_activity_events where user_id is not null and occurred_at >= :from", Map.of("from", now.minus(Duration.ofDays(1)))),
                count("select count(distinct user_id) from product_activity_events where user_id is not null and occurred_at >= :from", Map.of("from", now.minus(Duration.ofDays(7)))),
                count("select count(distinct user_id) from product_activity_events where user_id is not null and occurred_at >= :from", Map.of("from", now.minus(Duration.ofDays(30)))),
                count("select count(*) from app_users where last_activity_at >= :from", Map.of("from", now.minus(Duration.ofMinutes(5)))),
                count("select coalesce(max(online_users),0) from product_online_samples where sampled_at >= :from", Map.of("from", today)),
                count("select count(*) from lessons"), count("select count(*) from connection_requests where status='ACCEPTED'"),
                count("select count(*) from email_notifications where status='PENDING'"), count("select count(*) from email_notifications where status='FAILED'"));
    }

    public void recordActivity(User user, String eventType) {
        if (!metricsEnabled) return;
        Instant now = clock.instant();
        if (user.getLastActivityAt() != null && user.getLastActivityAt().plusSeconds(60).isAfter(now)) return;
        user.recordActivity(now); users.save(user);
        jdbc.sql("insert into product_activity_events(user_id,event_type,occurred_at) values (:user,:type,:now)")
                .param("user", user.getId()).param("type", eventType).param("now", timestamp(now)).update();
    }

    public void recordLogin(User user) { user.recordLogin(); users.save(user); recordActivity(user, "LOGIN"); }

    @Transactional
    public void sampleOnline() {
        if (!metricsEnabled) return;
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        long online = count("select count(*) from app_users where last_activity_at >= :from", Map.of("from", now.minus(Duration.ofMinutes(5))));
        jdbc.sql("insert into product_online_samples(sampled_at,online_users) values (:at,:online) on conflict(sampled_at) do update set online_users=excluded.online_users")
                .param("at", timestamp(now)).param("online", (int) online).update();
    }

    @Transactional
    public void recordAnonymousVisit(String ip, String userAgent) {
        if (!metricsEnabled || metricsHmacKey.length() < 32 || ip == null || ip.isBlank()) return;
        LocalDate day = LocalDate.now(ZoneId.of("Europe/Moscow")); Instant now = clock.instant();
        String fingerprint = dailyFingerprint(day, ip, userAgent == null ? "" : userAgent);
        int inserted = jdbc.sql("insert into anonymous_visit_fingerprints(fingerprint_hash,visit_day,expires_at,created_at) values (:hash,:day,:expires,:now) on conflict(fingerprint_hash,visit_day) do nothing")
                .param("hash", fingerprint).param("day", day).param("expires", timestamp(now.plus(Duration.ofHours(48)))).param("now", timestamp(now)).update();
        jdbc.sql("insert into product_daily_metrics(metric_day,page_views,anonymous_uniques,updated_at) values (:day,1,:unique,:now) on conflict(metric_day) do update set page_views=product_daily_metrics.page_views+1,anonymous_uniques=product_daily_metrics.anonymous_uniques+:unique,updated_at=:now")
                .param("day", day).param("unique", inserted).param("now", timestamp(now)).update();
    }

    @Transactional
    public void cleanupMetrics() {
        if (!metricsEnabled) return;
        Instant now = clock.instant();
        jdbc.sql("delete from anonymous_visit_fingerprints where expires_at < :now").param("now", timestamp(now)).update();
        jdbc.sql("delete from product_activity_events where occurred_at < :cutoff").param("cutoff", timestamp(now.minus(Duration.ofDays(90)))).update();
        jdbc.sql("delete from product_online_samples where sampled_at < :cutoff").param("cutoff", timestamp(now.minus(Duration.ofDays(30)))).update();
    }

    @Transactional
    public int purgeDueUsers() {
        List<Long> due = jdbc.sql("select id from app_users where deletion_scheduled_at <= :now and deletion_completed_at is null order by id for update skip locked")
                .param("now", timestamp(clock.instant())).query(Long.class).list();
        for (Long userId : due) purgeUser(userId);
        return due.size();
    }

    @Transactional
    public void expireRestrictions() {
        Instant now = clock.instant();
        List<Long> affected = jdbc.sql("select distinct user_id from user_account_restrictions where lifted_at is null and ends_at <= :now")
                .param("now", timestamp(now)).query(Long.class).list();
        if (affected.isEmpty()) return;
        jdbc.sql("update user_account_restrictions set lifted_at=:now where lifted_at is null and ends_at <= :now")
                .param("now", timestamp(now)).update();
        for (Long userId : affected) {
            User user = users.findById(userId).orElse(null);
            if (user != null && user.getDeletionScheduledAt() == null) { user.setEnabled(true); user.invalidateSessions(); }
        }
    }

    private void purgeUser(long userId) {
        List<String> attachments = jdbc.sql("select a.stored_name from attachments a join lessons l on l.id=a.lesson_id where l.teacher_id=:id or l.student_id=:id")
                .param("id", userId).query(String.class).list();
        List<String> images = jdbc.sql("select i.stored_name from whiteboard_images i join whiteboard_objects o on o.id=i.object_id join whiteboards b on b.id=o.board_id join lessons l on l.id=b.lesson_id where l.teacher_id=:id or l.student_id=:id")
                .param("id", userId).query(String.class).list();
        jdbc.sql("update lesson_payment_records set lesson_id=null where lesson_id in (select id from lessons where teacher_id=:id or student_id=:id)").param("id", userId).update();
        jdbc.sql("delete from lessons where teacher_id=:id or student_id=:id").param("id", userId).update();
        jdbc.sql("delete from lesson_series where teacher_id=:id or student_id=:id").param("id", userId).update();
        jdbc.sql("delete from lesson_subscriptions where teacher_id=:id or student_id=:id").param("id", userId).update();
        jdbc.sql("delete from connection_requests where teacher_id=:id or student_id=:id").param("id", userId).update();
        jdbc.sql("delete from teacher_profiles where user_id=:id").param("id", userId).update();
        jdbc.sql("delete from external_identities where user_id=:id").param("id", userId).update();
        jdbc.sql("delete from email_verification_tokens where user_id=:id").param("id", userId).update();
        jdbc.sql("delete from password_reset_tokens where user_id=:id").param("id", userId).update();
        jdbc.sql("delete from admin_support_grants where user_id=:id").param("id", userId).update();
        jdbc.sql("update email_notifications set student_id=null where student_id=:id").param("id", userId).update();
        jdbc.sql("update email_notifications set teacher_id=null where teacher_id=:id").param("id", userId).update();
        jdbc.sql("update product_activity_events set user_id=null where user_id=:id").param("id", userId).update();
        String tombstone = "deleted-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("update app_users set username=:username,email=null,password_hash=null,display_name='Удалённый пользователь',enabled=false,auth_version=auth_version+1,deletion_completed_at=:now,deletion_scheduled_at=null,must_change_password=false where id=:id")
                .param("username", tombstone).param("now", timestamp(clock.instant())).param("id", userId).update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deleteStored(attachments, storageRoot); deleteStored(images, storageRoot.resolve("boards")); }
        });
    }

    private void deleteStored(Collection<String> names, Path root) {
        for (String name : names) try { Path target = root.resolve(name).normalize(); if (target.getParent().equals(root)) Files.deleteIfExists(target); } catch (Exception ignored) { }
    }

    public List<User> users(String query, Role role, String state, int page, int size) {
        return users.searchForAdmin(normalized(query), role, state,
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50), org.springframework.data.domain.Sort.by("createdAt").descending())).getContent();
    }

    public User requireUser(long id) { return users.findById(id).orElseThrow(() -> new NoSuchElementException("Пользователь не найден")); }
    public boolean hasVk(User user) { return identities.existsByUserIdAndProvider(user.getId(), "VK"); }

    public void audit(long adminId, String action, String targetType, Long targetId, String reason, String sourceIp, Map<String, ?> details) {
        jdbc.sql("insert into admin_audit_log(admin_id,action,target_type,target_id,reason,request_id,source_ip,details,created_at) values (:admin,:action,:type,:target,:reason,:request,:ip,cast(:details as jsonb),:now)")
                .param("admin", adminId).param("action", action).param("type", targetType).param("target", targetId)
                .param("reason", blankToNull(reason)).param("request", UUID.randomUUID()).param("ip", blankToNull(sourceIp))
                .param("details", "{}").param("now", timestamp(clock.instant())).update();
    }

    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }
    private long count(String sql, Map<String, ?> params) { var statement = jdbc.sql(sql); for (var entry : params.entrySet()) statement = statement.param(entry.getKey(), entry.getValue() instanceof Instant instant ? timestamp(instant) : entry.getValue()); return statement.query(Long.class).single(); }
    private void validateAdmin(String username, String password) { if (!normalized(username).matches("[a-z0-9._-]{3,40}")) throw new IllegalArgumentException("Некорректный логин"); if (password == null || password.length() < 14 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("Пароль администратора должен содержать 14–72 байта"); if (encryptionKey.length() < 32) throw new IllegalStateException("Не настроен ключ шифрования TOTP"); }
    private List<String> createRecoveryCodes(long id, Instant now) { List<String> result = new ArrayList<>(); for (int item = 0; item < 10; item++) { String code = randomCode(); result.add(code); jdbc.sql("insert into admin_recovery_codes(admin_id,code_hash,created_at) values (:admin,:hash,:now)").param("admin", id).param("hash", encoder.encode(code)).param("now", timestamp(now)).update(); } return result; }
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private String randomCode() { byte[] value = new byte[8]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value).toUpperCase(Locale.ROOT); }
    private String randomTemporaryPassword() { byte[] value = new byte[18]; RANDOM.nextBytes(value); return "Rh-" + Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String randomInvitationToken() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private boolean limited(String key, Duration window, int max) { synchronized (loginAttempts) { Deque<Instant> queue = loginAttempts.computeIfAbsent(key, ignored -> new ArrayDeque<>()); Instant cutoff = clock.instant().minus(window); while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) queue.removeFirst(); return queue.size() >= max; } }
    private void markAttempt(String key) { synchronized (loginAttempts) { loginAttempts.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(clock.instant()); } }
    private String encrypt(String plain) { try { byte[] iv = new byte[12]; RANDOM.nextBytes(iv); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv)); return Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException("Не удалось зашифровать TOTP", ex); } }
    private String decrypt(String value) { try { String[] pieces = value.split("\\."); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getUrlDecoder().decode(pieces[0]))); return new String(cipher.doFinal(Base64.getUrlDecoder().decode(pieces[1])), StandardCharsets.UTF_8); } catch (Exception ex) { throw new IllegalStateException("Не удалось расшифровать TOTP", ex); } }
    private SecretKeySpec key() { try { return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)), "AES"); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String requiredReason(String value) { if (value == null || value.trim().length() < 10 || value.trim().length() > 500) throw new IllegalArgumentException("Укажите причину длиной от 10 до 500 символов"); return value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private boolean safeEquals(String left, String right) { return right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)); }
    private String dailyFingerprint(LocalDate day, String ip, String agent) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(metricsHmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal((day + "|" + ip + "|" + agent).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("Не удалось записать техническую метрику", ex); }
    }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }

    private record AdminAccount(long id, String username, String passwordHash, String totpSecret, boolean enabled, long authVersion) { }
    private record RecoveryCode(long id, String hash) { }
    public record BootstrapResult(long adminId, String totpSecret, List<String> recoveryCodes) { }
    public record PendingLogin(long adminId, String username) { }
    public record ManualUserResult(User user, String temporaryPassword) { }
    public record InvitationResult(UUID id, String link, String email, Role role) { }
    public record InvitationView(UUID id, String email, Role role, Instant expiresAt) { }
    public record AdminAccountView(long id, String username, boolean enabled, long authVersion, Instant lastLoginAt, Instant createdAt) { }
    public record Dashboard(long totalUsers, long teachers, long students, long activeUsers, long disabledUsers, long pendingDeletion,
                            long registrationsToday, long dau, long wau, long mau, long onlineNow, long peakOnlineToday,
                            long lessons, long activeConnections, long pendingEmails, long failedEmails) { }
}
