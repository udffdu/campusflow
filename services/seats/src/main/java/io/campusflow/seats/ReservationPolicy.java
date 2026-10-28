package io.campusflow.seats;

import static io.campusflow.seats.ReservationRuleException.Code.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Parameterized time checks only: successful validation does not grant access or reserve a seat. */
public final class ReservationPolicy {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private final Duration maxAdvance;
    private final Duration maxDuration;
    private final Clock clock;

    public ReservationPolicy(Duration maxAdvance, Duration maxDuration, Clock clock) {
        this.maxAdvance = Objects.requireNonNull(maxAdvance, "maxAdvance");
        this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAdvance.isNegative() || maxDuration.isNegative() || maxDuration.isZero()
                || maxDuration.getNano() != 0 || maxDuration.getSeconds() % 3600 != 0) {
            throw new IllegalArgumentException("提前窗口须非负，时长上限须为正整数小时");
        }
    }

    public void validate(TimeRange range) {
        Objects.requireNonNull(range, "range");
        if (!wholeHour(range.startAt()) || !wholeHour(range.endAt())) {
            throw new ReservationRuleException(NOT_WHOLE_HOUR, "预约起止时间须为上海时区整点");
        }
        Instant now = clock.instant();
        if (range.startAt().isBefore(now)) {
            throw new ReservationRuleException(IN_PAST, "不能预约已开始的时段");
        }
        if (range.startAt().isAfter(now.plus(maxAdvance))) {
            throw new ReservationRuleException(ADVANCE_WINDOW, "预约开始时间超出可提前预约窗口");
        }
        if (range.duration().compareTo(maxDuration) > 0) {
            throw new ReservationRuleException(DURATION_LIMIT, "预约时长超出单次上限");
        }
    }

    private static boolean wholeHour(Instant instant) {
        var local = instant.atZone(CAMPUS_ZONE);
        return local.getMinute() == 0 && local.getSecond() == 0 && local.getNano() == 0;
    }
}
