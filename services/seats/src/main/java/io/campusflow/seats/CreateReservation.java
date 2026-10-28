package io.campusflow.seats;

import java.util.Objects;
import java.util.UUID;

/** Internal input. The application must authenticate the user and validate D resource facts first. */
public record CreateReservation(UUID userId, UUID roomId, UUID seatId, TimeRange period, long resourceVersion) {
    public CreateReservation {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(period, "period");
        if (resourceVersion < 1) throw new IllegalArgumentException("resourceVersion must be positive");
    }
}
