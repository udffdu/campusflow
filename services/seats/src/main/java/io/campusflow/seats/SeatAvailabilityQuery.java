package io.campusflow.seats;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Internal, partial calculation: opening hours, base validity and M1 personal occupancy only.
 * Not an HTTP API, authentication boundary, or a complete D/M2/M3 availability guarantee.
 */
public final class SeatAvailabilityQuery {
    private final ReservationStore store;
    private final ReservationPolicy policy;

    public SeatAvailabilityQuery(ReservationStore store, ReservationPolicy policy) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Result query(AccessScope scope, RoomSnapshot room, List<SeatSnapshot> seats, TimeRange period) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(room, "room");
        if (!scope.allowedRoomIds().contains(room.id())) {
            throw new SecurityException("教室不在已验证的授权范围内");
        }
        policy.validate(period);
        var candidates = List.copyOf(Objects.requireNonNull(seats, "seats"));
        var ids = new HashSet<UUID>();
        for (var seat : candidates) {
            if (!seat.roomId().equals(room.id()) || !seat.departmentId().equals(room.departmentId())
                    || !ids.add(seat.id())) {
                throw new IllegalArgumentException("座位快照的教室、院系或唯一标识不一致");
            }
        }
        if (!room.effectiveActive()) return new Result(Reason.ROOM_INACTIVE, List.of());
        if (!room.openingHours().covers(period)) return new Result(Reason.OUTSIDE_OPENING_HOURS, List.of());
        var occupancy = store.personalOccupancy(scope.userId(), room.id(), period);
        if (occupancy.userConflict()) return new Result(Reason.USER_TIME_CONFLICT, List.of());
        var options = candidates.stream().filter(SeatSnapshot::effectiveActive)
                .filter(seat -> !occupancy.occupiedSeatIds().contains(seat.id()))
                .sorted(Comparator.comparing(SeatSnapshot::code).thenComparing(SeatSnapshot::id))
                .map(seat -> new SeatOption(seat.id(), seat.code())).toList();
        return new Result(Reason.NONE, options);
    }

    /** Only a future authenticated adapter may supply this scope; never bind it from browser input. */
    public record AccessScope(UUID userId, Set<UUID> allowedRoomIds) {
        public AccessScope {
            Objects.requireNonNull(userId, "userId");
            allowedRoomIds = Set.copyOf(Objects.requireNonNull(allowedRoomIds, "allowedRoomIds"));
        }
    }

    /** Typed M1 input, not a D entity. The adapter must verify D's resource type when mapping. */
    public record RoomSnapshot(UUID id, UUID departmentId, boolean effectiveActive, OpeningSchedule openingHours) {
        public RoomSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(departmentId, "departmentId");
            Objects.requireNonNull(openingHours, "openingHours");
        }
    }

    public record SeatSnapshot(UUID id, UUID roomId, UUID departmentId, String code, boolean effectiveActive) {
        public SeatSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(roomId, "roomId");
            Objects.requireNonNull(departmentId, "departmentId");
            if (code == null || code.isBlank()) throw new IllegalArgumentException("座位编号不能为空");
        }
    }

    public enum Reason { NONE, ROOM_INACTIVE, OUTSIDE_OPENING_HOURS, USER_TIME_CONFLICT }

    /** No identities or reservation details of other users leave the occupancy calculation. */
    public record SeatOption(UUID id, String code) {}

    public record Result(Reason reason, List<SeatOption> seats) {
        public Result {
            Objects.requireNonNull(reason, "reason");
            seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        }
    }
}
