package io.campusflow.seats;

import java.time.Instant;
import java.util.UUID;

public record ReservationRecord(UUID id, UUID userId, UUID roomId, UUID seatId, TimeRange period,
                                ReservationStatus status, String source, long resourceVersion,
                                long version, Instant createdAt) {}
