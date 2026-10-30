package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyCheckInCodeTest {
    private final byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final UUID room = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test void codeIsStableForRoomAndShanghaiDateButSeparatedByRoomAndDay() {
        var codes = new DailyCheckInCode(secret, Clock.fixed(Instant.parse("2026-10-30T15:59:59Z"), ZoneOffset.UTC));
        String current = codes.codeFor(room, LocalDate.parse("2026-10-30"));
        assertTrue(current.matches("[0-9]{6}"));
        assertEquals(current, codes.codeFor(room, LocalDate.parse("2026-10-30")));
        assertNotEquals(current, codes.codeFor(UUID.randomUUID(), LocalDate.parse("2026-10-30")));
        assertNotEquals(current, codes.codeFor(room, LocalDate.parse("2026-10-31")));
    }

    @Test void currentCodeRotatesExactlyAtShanghaiMidnight() {
        var before = new DailyCheckInCode(secret, Clock.fixed(Instant.parse("2026-10-30T15:59:59Z"), ZoneOffset.UTC)).current(room);
        var after = new DailyCheckInCode(secret, Clock.fixed(Instant.parse("2026-10-30T16:00:00Z"), ZoneOffset.UTC)).current(room);
        assertEquals(LocalDate.parse("2026-10-30"), before.validDate());
        assertEquals(Instant.parse("2026-10-30T16:00:00Z"), before.expiresAt());
        assertEquals(LocalDate.parse("2026-10-31"), after.validDate());
        assertNotEquals(before.code(), after.code());
    }

    @Test void rejectsShortSecretsAndMalformedCandidates() {
        assertThrows(IllegalArgumentException.class, () -> new DailyCheckInCode(new byte[31], Clock.systemUTC()));
        var codes = new DailyCheckInCode(secret, Clock.fixed(Instant.parse("2026-10-30T01:00:00Z"), ZoneOffset.UTC));
        assertFalse(codes.matchesCurrent(room, null));
        assertFalse(codes.matchesCurrent(room, "12345"));
        assertFalse(codes.matchesCurrent(room, "12345a"));
    }
}
