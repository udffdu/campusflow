package io.campusflow.seats;

import java.util.Objects;
import java.util.UUID;

/** Authenticated user and user intent; D metadata is acquired only for a new request. */
public record ReservationIntent(UUID userId,UUID roomId,UUID seatId,TimeRange period) {
    public ReservationIntent { Objects.requireNonNull(userId);Objects.requireNonNull(roomId);Objects.requireNonNull(seatId);Objects.requireNonNull(period); }
}
