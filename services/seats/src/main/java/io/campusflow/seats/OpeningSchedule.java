package io.campusflow.seats;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** M1 calculation over weekly local-time inputs, not a second resource catalog. */
public final class OpeningSchedule {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private final List<Interval> intervals;

    public OpeningSchedule(List<Interval> intervals) {
        this.intervals = List.copyOf(Objects.requireNonNull(intervals, "intervals")).stream()
                .sorted(Comparator.comparing(Interval::day).thenComparing(Interval::start)).toList();
        Interval previous = null;
        for (var interval : this.intervals) {
            if (previous != null && previous.day() == interval.day()
                    && interval.start().isBefore(previous.end())) {
                throw new IllegalArgumentException("同一天的开放区间不能重叠");
            }
            previous = interval;
        }
    }

    /** Empty schedules are closed; adjacent intervals may jointly cover the entire request. */
    public boolean covers(TimeRange period) {
        Objects.requireNonNull(period, "period");
        var date = period.startAt().atZone(CAMPUS_ZONE).toLocalDate();
        var coveredUntil = period.startAt();
        for (var interval : intervals) {
            if (interval.day() != date.getDayOfWeek()) continue;
            var intervalStart = date.atTime(interval.start()).atZone(CAMPUS_ZONE).toInstant();
            var intervalEnd = date.atTime(interval.end()).atZone(CAMPUS_ZONE).toInstant();
            if (!intervalEnd.isAfter(coveredUntil)) continue;
            if (intervalStart.isAfter(coveredUntil)) return false;
            coveredUntil = intervalEnd;
            if (!coveredUntil.isBefore(period.endAt())) return true;
        }
        // LocalTime intervals cannot express end=24:00. Do not invent cross-midnight coverage.
        return false;
    }

    public record Interval(DayOfWeek day, LocalTime start, LocalTime end) {
        public Interval {
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.isBefore(end) || start.getSecond() != 0 || start.getNano() != 0
                    || end.getSecond() != 0 || end.getNano() != 0) {
                throw new IllegalArgumentException("开放区间须按分钟设置，结束晚于开始");
            }
        }
    }
}
