package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;
import static io.campusflow.seats.OpeningScheduleTest.*;
import static io.campusflow.seats.SeatAvailabilityQuery.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

@Timeout(20)
class SeatAvailabilityQueryTest {
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static final Clock CLOCK = Clock.fixed(
            OffsetDateTime.parse("2026-10-28T08:20+08:00").toInstant(), ZoneOffset.UTC);
    private final UUID user = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID department = UUID.randomUUID();
    private final UUID seatId = UUID.randomUUID();
    private final UUID secondSeatId = UUID.randomUUID();
    private ReservationStore store;
    private SeatAvailabilityQuery query;
    private RoomSnapshot room;
    private List<SeatSnapshot> seats;
    private AccessScope scope;

    @BeforeAll static void database() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("m1").defaultSchema("m1").load().migrate();
    }

    @AfterAll static void closeDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach void reset() {
        jdbc.execute("truncate m1.outbox_events, m1.reservation_requests, m1.reservations");
        var policy = new ReservationPolicy(Duration.ofHours(24), Duration.ofHours(4), CLOCK);
        store = new ReservationStore(dataSource, policy, CLOCK, Duration.ofSeconds(2));
        query = new SeatAvailabilityQuery(store, policy);
        room = new RoomSnapshot(roomId, department, true,
                new OpeningSchedule(List.of(interval("09:00", "18:00"))));
        // Intentionally reversed: output ordering is independent of catalog order.
        seats = List.of(new SeatSnapshot(secondSeatId, roomId, department, "02", true),
                new SeatSnapshot(seatId, roomId, department, "01", true));
        scope = new AccessScope(user, Set.of(roomId));
    }

    @Test void bookingAfterQueryChangesNextQueryWithoutReservingOnRead() {
        assertEquals(List.of(seatId, secondSeatId), ids(search("09:00", "11:00")));
        assertEquals(List.of(), databaseSnapshot());
        reserve(UUID.randomUUID(), roomId, seatId, "09:00", "11:00");
        var before = databaseSnapshot();
        var result = search("09:00", "11:00");
        assertEquals(Reason.NONE, result.reason());
        assertEquals(List.of(secondSeatId), ids(result));
        assertEquals(before, databaseSnapshot(), "查询不得更改预约、请求或事件");
    }

    @ParameterizedTest
    @CsvSource({"CONFIRMED,true", "CHECKED_IN,true", "COMPLETED,false", "NO_SHOW,false", "DISRUPTED,false"})
    void occupancyReadsAllFiveStatusFixtures(String status, boolean blocks) {
        var record = reserve(UUID.randomUUID(), roomId, seatId, "09:00", "11:00");
        // Test fixtures only; production state transition APIs are not implemented here.
        jdbc.update("update m1.reservations set status=? where id=?", status, record.id());
        assertEquals(blocks ? List.of(secondSeatId) : List.of(seatId, secondSeatId), ids(search("10:00", "12:00")));
    }

    @Test void ownOverlapInAnotherRoomReturnsExplicitConflict() {
        reserve(user, UUID.randomUUID(), UUID.randomUUID(), "09:00", "11:00");
        var result = search("10:00", "12:00");
        assertEquals(Reason.USER_TIME_CONFLICT, result.reason());
        assertTrue(result.seats().isEmpty());
    }

    @Test void terminalOwnReservationDoesNotBlockAnotherRoom() {
        var record = reserve(user, UUID.randomUUID(), UUID.randomUUID(), "09:00", "11:00");
        jdbc.update("update m1.reservations set status='NO_SHOW' where id=?", record.id());
        assertEquals(List.of(seatId, secondSeatId), ids(search("10:00", "12:00")));
    }

    @Test void adjacentOwnAndSeatReservationsAllowFollowingPeriod() {
        reserve(user, roomId, seatId, "09:00", "10:00");
        assertEquals(List.of(seatId, secondSeatId), ids(search("10:00", "11:00")));
    }

    @Test void otherRoomsAndFutureReservationsDoNotHideSeats() {
        reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "09:00", "11:00");
        reserve(UUID.randomUUID(), roomId, seatId, "12:00", "13:00");
        assertEquals(List.of(seatId, secondSeatId), ids(search("10:00", "12:00")));
    }

    @Test void entirePeriodMustBeOpen() {
        room = new RoomSnapshot(roomId, department, true,
                new OpeningSchedule(List.of(interval("09:00", "12:00"))));
        assertEquals(List.of(seatId, secondSeatId), ids(search("11:00", "12:00")));
        assertEquals(Reason.OUTSIDE_OPENING_HOURS, search("11:00", "13:00").reason());
        assertTrue(search("11:00", "13:00").seats().isEmpty());
    }

    @Test void gapsAndEmptyRulesProduceClosedResult() {
        room = new RoomSnapshot(roomId, department, true,
                new OpeningSchedule(List.of(interval("09:00", "10:00"), interval("11:00", "12:00"))));
        assertEquals(Reason.OUTSIDE_OPENING_HOURS, search("09:00", "12:00").reason());
        room = new RoomSnapshot(roomId, department, true, new OpeningSchedule(List.of()));
        assertEquals(Reason.OUTSIDE_OPENING_HOURS, search("09:00", "10:00").reason());
    }

    @Test void baseInvalidRoomAndSeatAreExcluded() {
        room = new RoomSnapshot(roomId, department, false, room.openingHours());
        assertEquals(Reason.ROOM_INACTIVE, search("09:00", "10:00").reason());
        assertTrue(search("09:00", "10:00").seats().isEmpty());
        room = new RoomSnapshot(roomId, department, true, room.openingHours());
        seats = List.of(new SeatSnapshot(seatId, roomId, department, "01", false), seats.getFirst());
        assertEquals(List.of(secondSeatId), ids(search("09:00", "10:00")));
    }

    @Test void absentOrUnapprovedScopeIsRejected() {
        assertThrows(NullPointerException.class,
                () -> query.query(null, room, seats, period("09:00", "10:00")));
        assertThrows(SecurityException.class, () -> query.query(new AccessScope(user, Set.of()),
                room, seats, period("09:00", "10:00")));
        // Resource ownership alone never grants reservation access.
        assertThrows(SecurityException.class, () -> query.query(new AccessScope(user, Set.of(UUID.randomUUID())),
                room, seats, period("09:00", "10:00")));
    }

    @Test void foreignRoomAndDepartmentOrDuplicateSeatIdsAreRejected() {
        for (var invalid : List.of(
                List.of(new SeatSnapshot(seatId, UUID.randomUUID(), department, "01", true)),
                List.of(new SeatSnapshot(seatId, roomId, UUID.randomUUID(), "01", true)),
                List.of(seats.getFirst(), seats.getFirst()))) {
            assertThrows(IllegalArgumentException.class,
                    () -> query.query(scope, room, invalid, period("09:00", "10:00")));
        }
    }

    @ParameterizedTest
    @CsvSource({"2026-10-28T09:30+08:00,2026-10-28T10:00+08:00,NOT_WHOLE_HOUR",
            "2026-10-28T07:00+08:00,2026-10-28T08:00+08:00,IN_PAST",
            "2026-10-28T09:00+08:00,2026-10-28T14:00+08:00,DURATION_LIMIT",
            "2026-10-29T09:00+08:00,2026-10-29T10:00+08:00,ADVANCE_WINDOW"})
    void queryAppliesConfiguredTimePolicy(String start, String end, ReservationRuleException.Code code) {
        var range = new TimeRange(OffsetDateTime.parse(start).toInstant(), OffsetDateTime.parse(end).toInstant());
        assertEquals(code, assertThrows(ReservationRuleException.class,
                () -> query.query(scope, room, seats, range)).code());
    }

    @Test void databaseFailureIsNotAnEmptySuccess() {
        seats = List.of();
        assertEquals(Reason.NONE, search("09:00", "10:00").reason());
        assertTrue(search("09:00", "10:00").seats().isEmpty());
        jdbc.execute("alter table m1.reservations rename to reservations_unavailable_fixture");
        try {
            assertEquals(ReservationPersistenceException.Code.STORAGE_UNAVAILABLE,
                    assertThrows(ReservationPersistenceException.class, () -> search("09:00", "10:00")).code());
        } finally {
            jdbc.execute("alter table m1.reservations_unavailable_fixture rename to reservations");
        }
    }

    private Result search(String start, String end) {
        return query.query(scope, room, seats, period(start, end));
    }

    @Test void powerFilterUsesExactSeatMarkerAndStillExcludesOccupiedSeats() {
        seats=List.of(new SeatSnapshot(seatId,roomId,department,"01",true,Set.of("POWER_OUTLET")),
                new SeatSnapshot(secondSeatId,roomId,department,"02",true,Set.of("电源","power_outlet")));
        var identity=new ReservationAccessPolicy.IdentitySnapshot(user,department,Set.of("seats.availability.read"));
        assertEquals(List.of(seatId),ids(query.queryForIdentity(identity,room,seats,period("09:00","11:00"),true)));
        assertEquals(List.of(seatId,secondSeatId),ids(query.queryForIdentity(identity,room,seats,period("09:00","11:00"),false)));
        reserve(UUID.randomUUID(),roomId,seatId,"09:00","11:00");
        assertTrue(query.queryForIdentity(identity,room,seats,period("09:00","11:00"),true).seats().isEmpty());
    }

    @Test void identityQueryCombinesRoomScopeAndExistingAvailabilityRules() {
        var foreignIdentity=new ReservationAccessPolicy.IdentitySnapshot(user,UUID.randomUUID(),Set.of("seats.availability.read"));
        assertThrows(SecurityException.class,()->query.queryForIdentity(foreignIdentity,room,seats,period("09:00","10:00"),false));
        room=new RoomSnapshot(roomId,department,true,room.openingHours(),ReservationAccessPolicy.BookingScope.CAMPUS);
        assertEquals(List.of(seatId,secondSeatId),ids(query.queryForIdentity(foreignIdentity,room,seats,period("09:00","10:00"),false)));
        room=new RoomSnapshot(roomId,department,false,room.openingHours(),ReservationAccessPolicy.BookingScope.CAMPUS);
        assertEquals(Reason.ROOM_INACTIVE,query.queryForIdentity(foreignIdentity,room,seats,period("09:00","10:00"),false).reason());
    }

    private ReservationRecord reserve(UUID owner, UUID room, UUID seat, String start, String end) {
        return store.create(new CreateReservation(owner, room, seat, period(start, end), 7), UUID.randomUUID().toString());
    }

    private static List<UUID> ids(Result result) {
        return result.seats().stream().map(SeatOption::id).toList();
    }

    private static List<String> databaseSnapshot() {
        return jdbc.queryForList("""
                select 'reservation:' || row_to_json(r)::text as data from m1.reservations r
                union all select 'request:' || row_to_json(q)::text from m1.reservation_requests q
                union all select 'event:' || row_to_json(e)::text from m1.outbox_events e order by data
                """, String.class);
    }
}
