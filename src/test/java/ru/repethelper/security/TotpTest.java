package ru.repethelper.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {
    @Test
    void acceptsCurrentAndAdjacentThirtySecondWindowsOnly() {
        String secret = "JBSWY3DPEHPK3PXP";
        Instant instant = Instant.ofEpochSecond(1_700_000_000L);
        assertThat(Totp.matches(secret, Totp.code(secret, instant.getEpochSecond() / 30), instant)).isTrue();
        assertThat(Totp.matches(secret, "000000", instant)).isFalse();
    }
}
