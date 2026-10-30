package io.campusflow.seats;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Business authorization over trusted D inputs. Does not authenticate a session or call D. */
public final class ReservationAccessPolicy {
    private ReservationAccessPolicy() {}

    public enum BookingScope { DEPARTMENT, CAMPUS }

    public enum Action {
        QUERY("seats.availability.read"), CREATE("seats.reservations.create"),
        READ_OWN("seats.reservations.read.own"), CHECK_IN("seats.reservations.read.own"),
        LEAVE_SEAT("seats.reservations.read.own"),
        DISPLAY_CHECK_IN_CODE("spaces.write");
        private final String permission;
        Action(String permission) { this.permission = permission; }
        public String permission() { return permission; }
    }

    /** Internal input from the future authenticated adapter, never from a browser-supplied identity. */
    public record IdentitySnapshot(UUID userId, UUID departmentId, Set<String> permissions) {
        public IdentitySnapshot {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(departmentId, "departmentId");
            permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        }
    }

    public static SeatAvailabilityQuery.AccessScope scopeFor(IdentitySnapshot identity, Action action,
                                                            SeatAvailabilityQuery.RoomSnapshot room) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(room, "room");
        if (!identity.permissions().contains(action.permission())) {
            throw new SecurityException("缺少座位业务权限");
        }
        if (room.bookingScope() == BookingScope.DEPARTMENT
                && !identity.departmentId().equals(room.departmentId())) {
            throw new SecurityException("教室仅向本院系开放预约");
        }
        return new SeatAvailabilityQuery.AccessScope(identity.userId(), Set.of(room.id()));
    }
}
