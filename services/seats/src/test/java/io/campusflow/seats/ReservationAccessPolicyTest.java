package io.campusflow.seats;

import static io.campusflow.seats.ReservationAccessPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationAccessPolicyTest {
    private final UUID user=UUID.randomUUID(), department=UUID.randomUUID(), otherDepartment=UUID.randomUUID();

    @Test void catalogOrAdministrativePermissionsDoNotGrantSeatAccess() {
        var identity=new IdentitySnapshot(user,department,Set.of("spaces.read","spaces.write","users.manage"));
        assertThrows(SecurityException.class,()->scopeFor(identity,Action.QUERY,room(department,BookingScope.CAMPUS)));
        assertThrows(SecurityException.class,()->scopeFor(identity,Action.CREATE,room(department,BookingScope.CAMPUS)));
    }

    @Test void queryAndCreateAreSeparatePermissions() {
        var identity=new IdentitySnapshot(user,department,Set.of("seats.availability.read"));
        var room=room(department,BookingScope.DEPARTMENT);
        assertEquals(Set.of(room.id()),scopeFor(identity,Action.QUERY,room).allowedRoomIds());
        assertThrows(SecurityException.class,()->scopeFor(identity,Action.CREATE,room));
    }

    @Test void departmentRoomsRejectOtherDepartmentsEvenWithAllBusinessPermissions() {
        var identity=new IdentitySnapshot(user,department,Set.of("seats.availability.read","seats.reservations.create"));
        var room=room(otherDepartment,BookingScope.DEPARTMENT);
        assertThrows(SecurityException.class,()->scopeFor(identity,Action.QUERY,room));
        assertThrows(SecurityException.class,()->scopeFor(identity,Action.CREATE,room));
    }

    @Test void campusRoomsAllowAnotherDepartmentButKeepTheAuthenticatedUser() {
        var identity=new IdentitySnapshot(user,department,Set.of("seats.availability.read","seats.reservations.create"));
        var room=room(otherDepartment,BookingScope.CAMPUS);
        assertEquals(user,scopeFor(identity,Action.QUERY,room).userId());
        assertEquals(Set.of(room.id()),scopeFor(identity,Action.CREATE,room).allowedRoomIds());
    }

    private SeatAvailabilityQuery.RoomSnapshot room(UUID owner,BookingScope scope) {
        return new SeatAvailabilityQuery.RoomSnapshot(UUID.randomUUID(),owner,true,new OpeningSchedule(List.of()),scope);
    }
}
