package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpeningScheduleTest {
    @ParameterizedTest
    @CsvSource({"09:00,12:00,true", "11:00,12:00,true", "11:00,13:00,false",
            "08:00,10:00,false", "12:00,13:00,false", "08:00,09:00,false"})
    void entirePeriodMustBeCovered(String start, String end, boolean expected) {
        var schedule = new OpeningSchedule(List.of(interval("09:00", "12:00")));
        assertEquals(expected, schedule.covers(period(start, end)));
    }

    @Test void adjacentUnsortedIntervalsCoverOneRequest() {
        var schedule = new OpeningSchedule(List.of(interval("10:00", "12:00"), interval("09:00", "10:00")));
        assertTrue(schedule.covers(period("09:00", "12:00")));
    }

    @Test void aGapCannotBeSkippedEvenWhenBothEndpointsAreOpen() {
        var schedule = new OpeningSchedule(List.of(interval("09:00", "10:00"), interval("11:00", "12:00")));
        assertFalse(schedule.covers(period("09:00", "12:00")));
    }

    @Test void earlierIntervalsDoNotHideALaterOpening() {
        var schedule = new OpeningSchedule(List.of(interval("07:00", "08:00"), interval("09:00", "12:00")));
        assertTrue(schedule.covers(period("09:00", "12:00")));
    }

    @Test void emptyRulesAndWrongWeekdayAreClosed() {
        assertFalse(new OpeningSchedule(List.of()).covers(period("09:00", "10:00")));
        var thursday = new OpeningSchedule.Interval(DayOfWeek.THURSDAY,
                LocalTime.of(9, 0), LocalTime.of(12, 0));
        assertFalse(new OpeningSchedule(List.of(thursday)).covers(period("09:00", "10:00")));
    }

    @Test void weekdayUsesShanghaiDateRatherThanUtcDate() {
        // 2026-10-28 00:00 Shanghai is still Tuesday in UTC.
        var schedule = new OpeningSchedule(List.of(interval("00:00", "02:00")));
        assertTrue(schedule.covers(period("00:00", "01:00")));
        var nextWeek = new TimeRange(OffsetDateTime.parse("2026-11-04T00:00+08:00").toInstant(),
                OffsetDateTime.parse("2026-11-04T01:00+08:00").toInstant());
        assertTrue(schedule.covers(nextWeek));
    }

    @Test void crossMidnightIsNotInventedFromUnrepresentableDayEnd() {
        var schedule = new OpeningSchedule(List.of(interval("22:00", "23:59"),
                new OpeningSchedule.Interval(DayOfWeek.THURSDAY, LocalTime.MIDNIGHT, LocalTime.of(2, 0))));
        assertFalse(schedule.covers(new TimeRange(OffsetDateTime.parse("2026-10-28T23:00+08:00").toInstant(),
                OffsetDateTime.parse("2026-10-29T01:00+08:00").toInstant())));
    }

    @ParameterizedTest
    @CsvSource({"12:00,09:00", "09:00,09:00", "09:00:01,12:00", "09:00,12:00:00.000000001"})
    void malformedIntervalsAreRejected(String start, String end) {
        assertThrows(IllegalArgumentException.class, () -> interval(start, end));
    }

    @Test void overlappingRulesAreRejectedRatherThanSilentlyReinterpreted() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpeningSchedule(List.of(interval("09:00", "11:00"), interval("10:00", "12:00"))));
    }

    static OpeningSchedule.Interval interval(String start, String end) {
        return new OpeningSchedule.Interval(DayOfWeek.WEDNESDAY, LocalTime.parse(start), LocalTime.parse(end));
    }

    static TimeRange period(String start, String end) {
        return new TimeRange(OffsetDateTime.parse("2026-10-28T" + start + "+08:00").toInstant(),
                OffsetDateTime.parse("2026-10-28T" + end + "+08:00").toInstant());
    }
}
