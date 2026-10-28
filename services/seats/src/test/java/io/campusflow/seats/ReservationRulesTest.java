package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;
import static io.campusflow.seats.ReservationRuleException.Code.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationRulesTest {
    private static Instant at(String local) {
        return OffsetDateTime.parse("2026-10-28T" + local + "+08:00").toInstant();
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(at(start), at(end));
    }

    private static ReservationPolicy policy(String now, long advanceHours, long durationHours) {
        return new ReservationPolicy(Duration.ofHours(advanceHours), Duration.ofHours(durationHours),
                Clock.fixed(at(now), ZoneOffset.UTC));
    }

    @Test void rejectsEmptyReversedAndMissingIntervals() {
        assertCode(INVALID_INTERVAL, () -> range("09:00:00", "09:00:00"));
        assertCode(INVALID_INTERVAL, () -> range("10:00:00", "09:00:00"));
        assertCode(INVALID_INTERVAL, () -> new TimeRange(null, at("10:00:00")));
        assertCode(INVALID_INTERVAL, () -> new TimeRange(at("09:00:00"), null));
    }

    @Test void adjacentReservationsDoNotConflictInEitherOrder() {
        var first = range("09:00:00", "10:00:00");
        var second = range("10:00:00", "11:00:00");
        assertFalse(first.overlaps(second));
        assertFalse(second.overlaps(first));
    }

    @Test void intersectingAndContainedIntervalsConflict() {
        var first = range("09:00:00", "12:00:00");
        var intersecting = range("11:00:00", "13:00:00");
        assertTrue(first.overlaps(intersecting));
        assertTrue(intersecting.overlaps(first));
        assertTrue(first.overlaps(range("10:00:00", "11:00:00")));
        assertTrue(first.overlaps(first));
        assertFalse(first.overlaps(range("13:00:00", "14:00:00")));
    }

    @ParameterizedTest
    @CsvSource({"CONFIRMED,true", "CHECKED_IN,true", "COMPLETED,false", "NO_SHOW,false", "DISRUPTED,false"})
    void statusControlsWhetherTheReservationBlocks(ReservationStatus status, boolean expected) {
        var held = range("09:00:00", "11:00:00");
        assertEquals(expected, status.blocks(held, range("10:00:00", "12:00:00")));
        assertFalse(status.blocks(held, range("11:00:00", "12:00:00")));
    }

    @Test void canBookLessThanOneHourAheadAndExactlyFourHours() {
        assertDoesNotThrow(() -> policy("08:20:00", 24, 4).validate(range("09:00:00", "13:00:00")));
    }

    @Test void windowConstrainsStartRatherThanEndAndIncludesItsUpperBoundary() {
        var nextDay = new TimeRange(at("08:00:00").plus(Duration.ofDays(1)),
                at("12:00:00").plus(Duration.ofDays(1)));
        assertDoesNotThrow(() -> policy("08:00:00", 24, 4).validate(nextDay));
    }

    @Test void nextDayOutsideTheConfiguredWindowIsRejected() {
        var nextDay = new TimeRange(at("09:00:00").plus(Duration.ofDays(1)),
                at("10:00:00").plus(Duration.ofDays(1)));
        assertCode(ADVANCE_WINDOW, () -> policy("08:20:00", 24, 4).validate(nextDay));
    }

    @Test void changingTheWindowChangesTheAcceptedReservation() {
        var request = range("11:00:00", "12:00:00");
        assertCode(ADVANCE_WINDOW, () -> policy("08:20:00", 2, 4).validate(request));
        assertDoesNotThrow(() -> policy("08:20:00", 3, 4).validate(request));
    }

    @Test void changingTheDurationLimitChangesTheAcceptedReservation() {
        var request = range("09:00:00", "14:00:00");
        assertCode(DURATION_LIMIT, () -> policy("08:20:00", 24, 4).validate(request));
        assertDoesNotThrow(() -> policy("08:20:00", 24, 5).validate(request));
    }

    @Test void pastIntervalsAreRejectedUsingTheInjectedClock() {
        assertCode(IN_PAST, () -> policy("09:00:01", 24, 4).validate(range("09:00:00", "10:00:00")));
        assertDoesNotThrow(() -> policy("09:00:00", 24, 4).validate(range("09:00:00", "10:00:00")));
    }

    @ParameterizedTest
    @CsvSource({"09:30:00,10:00:00", "09:00:00,10:30:00", "09:00:01,10:00:00", "09:00:00.000000001,10:00:00"})
    void wholeHoursAreCheckedForBothEndpoints(String start, String end) {
        assertCode(NOT_WHOLE_HOUR, () -> policy("08:20:00", 24, 4).validate(range(start, end)));
    }

    @Test void equivalentInstantsWithOtherOffsetsUseCampusLocalTime() {
        var request = new TimeRange(Instant.parse("2026-10-28T01:00:00Z"), Instant.parse("2026-10-28T02:00:00Z"));
        assertDoesNotThrow(() -> policy("08:20:00", 24, 4).validate(request));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1800, 5400})
    void rejectsInvalidDurationConfiguration(long seconds) {
        assertThrows(IllegalArgumentException.class, () -> new ReservationPolicy(Duration.ofHours(24),
                Duration.ofSeconds(seconds), Clock.systemUTC()));
    }

    @Test void rejectsNegativeAdvanceConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> policy("08:00:00", -1, 4));
    }

    private static void assertCode(ReservationRuleException.Code code, Runnable action) {
        assertEquals(code, assertThrows(ReservationRuleException.class, action::run).code());
    }
}
