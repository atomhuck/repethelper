package ru.repethelper.security;

import java.io.Serializable;
import java.time.Instant;

/** Separate from the user principal: it is only valid behind the private gateway. */
public record AdminSession(long adminId, Instant authenticatedAt, Instant lastSeenAt,
                           Instant totpConfirmedAt, long authVersion) implements Serializable {
    public AdminSession touch(Instant now) {
        return new AdminSession(adminId, authenticatedAt, now, totpConfirmedAt, authVersion);
    }
    public boolean expired(Instant now) {
        return lastSeenAt.plusSeconds(15 * 60).isBefore(now)
                || authenticatedAt.plusSeconds(8 * 60 * 60).isBefore(now);
    }
    public boolean recentlyConfirmed(Instant now) {
        return !totpConfirmedAt.plusSeconds(5 * 60).isBefore(now);
    }
    public AdminSession reconfirm(Instant now) {
        return new AdminSession(adminId, authenticatedAt, now, now, authVersion);
    }
}
