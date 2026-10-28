package io.campusflow.seats;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A positive half-open interval. Local opening hours are checked by the application layer. */
public record TimeRange(Instant startAt, Instant endAt) {
    public TimeRange {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new ReservationRuleException(ReservationRuleException.Code.INVALID_INTERVAL,
                    "起止时间必填，结束时间须晚于开始时间");
        }
    }

    public Duration duration() {
        return Duration.between(startAt, endAt);
    }

    public boolean overlaps(TimeRange other) {
        Objects.requireNonNull(other, "other");
        return startAt.isBefore(other.endAt) && other.startAt.isBefore(endAt);
    }
}
