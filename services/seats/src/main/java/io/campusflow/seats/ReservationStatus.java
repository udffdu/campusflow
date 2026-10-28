package io.campusflow.seats;

import java.util.Objects;

/** Status meanings confirmed by the M1-A owner; transitions are not implemented here. */
public enum ReservationStatus {
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    NO_SHOW,
    DISRUPTED;

    public boolean blocks(TimeRange reservation, TimeRange requested) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(requested, "requested");
        return (this == CONFIRMED || this == CHECKED_IN) && reservation.overlaps(requested);
    }
}
