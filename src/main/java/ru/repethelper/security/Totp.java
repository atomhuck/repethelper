package ru.repethelper.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

public final class Totp {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private Totp() { }

    public static String newSecret() {
        byte[] value = new byte[20]; new SecureRandom().nextBytes(value); return base32(value);
    }

    public static boolean matches(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long step = now.getEpochSecond() / 30;
        return code.equals(code(secret, step - 1)) || code.equals(code(secret, step)) || code.equals(code(secret, step + 1));
    }

    public static String code(String secret, long counter) {
        try {
            byte[] key = decode(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int value = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return "%06d".formatted(value % 1_000_000);
        } catch (Exception ex) { throw new IllegalStateException("Не удалось проверить одноразовый код", ex); }
    }

    private static String base32(byte[] input) {
        StringBuilder out = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte item : input) { buffer = (buffer << 8) | (item & 0xff); bits += 8; while (bits >= 5) { out.append(ALPHABET[(buffer >> (bits -= 5)) & 31]); } }
        if (bits > 0) out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        return out.toString();
    }

    private static byte[] decode(String value) {
        String source = value.replace("=", "").replaceAll("\\s", "").toUpperCase();
        ByteBuffer result = ByteBuffer.allocate(source.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : source.toCharArray()) {
            int index = new String(ALPHABET).indexOf(c); if (index < 0) throw new IllegalArgumentException("Некорректный TOTP-секрет");
            buffer = (buffer << 5) | index; bits += 5;
            if (bits >= 8) result.put((byte) ((buffer >> (bits -= 8)) & 0xff));
        }
        byte[] bytes = new byte[result.position()]; result.flip(); result.get(bytes); return bytes;
    }
}
