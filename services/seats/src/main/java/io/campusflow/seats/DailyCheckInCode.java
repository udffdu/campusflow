package io.campusflow.seats;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.*;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless daily room code derivation. The secret and generated codes are never persisted. */
public final class DailyCheckInCode {
    public static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    public static final int CODE_LENGTH = 6;
    private static final BigInteger MODULUS = BigInteger.TEN.pow(CODE_LENGTH);
    private final byte[] secret;
    private final Clock clock;

    public DailyCheckInCode(byte[] secret, Clock clock) {
        if (Objects.requireNonNull(secret, "secret").length < 32) {
            throw new IllegalArgumentException("M1签到密钥至少需要32个随机字节");
        }
        this.secret = secret.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DailyCode current(UUID roomId) {
        LocalDate date = LocalDate.now(clock.withZone(CAMPUS_ZONE));
        return new DailyCode(codeFor(roomId, date), date,
                date.plusDays(1).atStartOfDay(CAMPUS_ZONE).toInstant());
    }

    public String codeFor(UUID roomId, LocalDate date) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(date, "date");
        byte[] digest = mac("campusflow:m1:room-check-in:v1\n" + roomId + "\n" + date);
        int value = new BigInteger(1, digest).mod(MODULUS).intValueExact();
        return String.format(Locale.ROOT, "%0" + CODE_LENGTH + "d", value);
    }

    public boolean matchesCurrent(UUID roomId, String candidate) {
        if (candidate == null || !candidate.matches("[0-9]{" + CODE_LENGTH + "}")) return false;
        return MessageDigest.isEqual(current(roomId).code().getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256不可用", exception);
        }
    }

    public record DailyCode(String code, LocalDate validDate, Instant expiresAt) {}
}
