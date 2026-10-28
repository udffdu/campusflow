package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;
import static io.campusflow.seats.ReservationPersistenceException.Code.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Timeout(20)
class ReservationStoreTest {
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static Flyway flyway;
    private static JdbcTemplate jdbc;
    private static final Clock CLOCK = Clock.fixed(at("08:20:00"), ZoneOffset.UTC);
    private final UUID user = UUID.randomUUID();
    private final UUID room = UUID.randomUUID();
    private final UUID seat = UUID.randomUUID();
    private ReservationStore store;

    @BeforeAll static void database() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        flyway = Flyway.configure().dataSource(dataSource).schemas("m1").defaultSchema("m1").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
    }

    @AfterAll static void closeDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach void reset() {
        jdbc.execute("truncate m1.outbox_events, m1.reservation_requests, m1.reservations");
        store = store(CLOCK, Duration.ofSeconds(2));
    }

    @Test void createsReservationRequestAndPendingEventTogether() {
        var record = store.create(request(), "request-1");
        assertEquals(user, record.userId());
        assertEquals(room, record.roomId());
        assertEquals(seat, record.seatId());
        assertEquals(ReservationStatus.CONFIRMED, record.status());
        assertEquals("M1", record.source());
        assertEquals(1, record.version());
        assertEquals(7, record.resourceVersion());
        assertCounts(1, 1, 1);
        assertEquals(record.id().toString(), jdbc.queryForObject(
                "select payload->>'reservationId' from m1.outbox_events", String.class));
        assertEquals(user.toString(), jdbc.queryForObject(
                "select payload->>'userId' from m1.outbox_events", String.class));
        assertEquals("PENDING", jdbc.queryForObject("select status from m1.outbox_events", String.class));
    }

    @Test void migrationReplayPreservesExistingReservation() {
        var record = store.create(request(), "migration");
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(record, store.findForUser(user, record.id()).orElseThrow());
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema='public' and table_name='reservations'", Integer.class));
    }

    @Test void lostResponseRetryReturnsSameRecordAndNoDuplicateEvent() {
        var first = store.create(request(), "retry");
        var replay = store.create(request(), "retry");
        assertEquals(first, replay);
        assertCounts(1, 1, 1);
    }

    @Test void replaySurvivesTimeAndResourceMetadataChanges() {
        var request = request();
        var first = store.create(request, "stable-intent");
        var later = store(Clock.offset(CLOCK, Duration.ofDays(2)), Duration.ofSeconds(2));
        var newerMetadata = new CreateReservation(user, room, seat, request.period(), 8);
        assertEquals(first, later.create(newerMetadata, "stable-intent"));
        assertCounts(1, 1, 1);
    }

    @Test void sameKeyWithDifferentIntentIsRejected() {
        store.create(request(), "same-key");
        var changed = new CreateReservation(user, room, UUID.randomUUID(), request().period(), 7);
        assertCode(IDEMPOTENCY_CONFLICT, () -> store.create(changed, "same-key"));
        assertCounts(1, 1, 1);
    }

    @Test void keysAreIsolatedByUser() {
        store.create(request(), "per-user");
        var other = new CreateReservation(UUID.randomUUID(), room, UUID.randomUUID(), request().period(), 7);
        store.create(other, "per-user");
        assertCounts(2, 2, 2);
    }

    @Test void otherUsersCannotReadReservationById() {
        var record = store.create(request(), "privacy");
        assertTrue(store.findForUser(UUID.randomUUID(), record.id()).isEmpty());
        assertTrue(store.findForUser(user, UUID.randomUUID()).isEmpty());
    }

    @Test void overlappingSeatIsRejected() {
        store.create(request(), "seat-first");
        var other = new CreateReservation(UUID.randomUUID(), room, seat, request().period(), 7);
        assertCode(SEAT_TIME_CONFLICT, () -> store.create(other, "seat-second"));
        assertCounts(1, 1, 1);
    }

    @Test void overlappingUserAcrossRoomsIsRejectedButAdjacentSwitchIsAllowed() {
        store.create(request(), "user-first");
        var otherRoom = UUID.randomUUID();
        var overlapping = new CreateReservation(user, otherRoom, UUID.randomUUID(), request().period(), 7);
        assertCode(USER_TIME_CONFLICT, () -> store.create(overlapping, "user-second"));
        var adjacent = new CreateReservation(user, otherRoom, UUID.randomUUID(),
                new TimeRange(at("11:00:00"), at("12:00:00")), 7);
        store.create(adjacent, "user-adjacent");
        assertCounts(2, 2, 2);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"COMPLETED", "NO_SHOW", "DISRUPTED"})
    void terminalFixturesDoNotHoldSeat(ReservationStatus status) {
        var record = store.create(request(), "terminal-fixture");
        // Fixture only: this does not implement B's transition protocol.
        jdbc.update("update m1.reservations set status=? where id=?", status.name(), record.id());
        store.create(new CreateReservation(UUID.randomUUID(), room, seat, request().period(), 7), "new-user");
        assertCounts(2, 2, 2);
    }

    @Test void eventWriteFailureRollsBackReservationAndRequest() {
        jdbc.execute("create function m1.reject_event() returns trigger language plpgsql as $$ "
                + "begin raise exception 'injected event write failure'; end; $$");
        jdbc.execute("create trigger reject_event before insert on m1.outbox_events "
                + "for each row execute function m1.reject_event()");
        try {
            assertThrows(DataAccessException.class, () -> store.create(request(), "rollback"));
            assertCounts(0, 0, 0);
        } finally {
            jdbc.execute("drop trigger reject_event on m1.outbox_events");
            jdbc.execute("drop function m1.reject_event()");
        }
        store.create(request(), "rollback");
        assertCounts(1, 1, 1);
    }

    @Test void invalidTimeAndKeyLeaveNoWrites() {
        var past = new CreateReservation(user, room, seat,
                new TimeRange(at("07:00:00"), at("08:00:00")), 7);
        assertThrows(ReservationRuleException.class, () -> store.create(past, "past"));
        assertThrows(IllegalArgumentException.class, () -> store.create(request(), "bad key"));
        assertCounts(0, 0, 0);
    }

    @Test void heldRoomLockCausesRetryableFailureAndNoWrites() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, OccupancyLocks.roomKey(room));
                statement.execute();
            }
            var impatient = store(CLOCK, Duration.ofMillis(200));
            assertCode(STORAGE_UNAVAILABLE, () -> impatient.create(request(), "locked"));
            assertCounts(0, 0, 0);
            holder.rollback();
        }
        store.create(request(), "locked");
        assertCounts(1, 1, 1);
    }

    @Test void concurrentUsersCompetingForOneSeatHaveOneWinner() throws Exception {
        var other = new CreateReservation(UUID.randomUUID(), room, seat, request().period(), 7);
        var results = race(() -> independentCreate(request(), "seat-a"), () -> independentCreate(other, "seat-b"));
        assertEquals(1, results.stream().filter(result -> result.id() != null).count());
        assertEquals(List.of(SEAT_TIME_CONFLICT), results.stream().map(Outcome::error).filter(Objects::nonNull).toList());
        assertCounts(1, 1, 1);
    }

    @Test void concurrentUserAcrossRoomsHasOneWinner() throws Exception {
        var other = new CreateReservation(user, UUID.randomUUID(), UUID.randomUUID(), request().period(), 7);
        var results = race(() -> independentCreate(request(), "room-a"), () -> independentCreate(other, "room-b"));
        assertEquals(1, results.stream().filter(result -> result.id() != null).count());
        assertEquals(List.of(USER_TIME_CONFLICT), results.stream().map(Outcome::error).filter(Objects::nonNull).toList());
        assertCounts(1, 1, 1);
    }

    @Test void concurrentIdenticalRequestsReturnOneReservation() throws Exception {
        var results = race(() -> independentCreate(request(), "parallel-retry"),
                () -> independentCreate(request(), "parallel-retry"));
        assertNotNull(results.getFirst().id());
        assertEquals(results.getFirst().id(), results.getLast().id());
        assertCounts(1, 1, 1);
    }

    @Test void databaseRejectsInvalidStatusAndReversedInterval() {
        String sql = "insert into m1.reservations "
                + "(id,user_id,room_id,seat_id,start_at,end_at,status,resource_version,created_at,updated_at) "
                + "values(?,?,?,?,?,?,?,7,?,?)";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, UUID.randomUUID(), user,
                room, seat, Timestamp.from(at("09:00:00")), Timestamp.from(at("11:00:00")), "UNKNOWN",
                Timestamp.from(CLOCK.instant()), Timestamp.from(CLOCK.instant())));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, UUID.randomUUID(), user,
                room, seat, Timestamp.from(at("11:00:00")), Timestamp.from(at("09:00:00")), "CONFIRMED",
                Timestamp.from(CLOCK.instant()), Timestamp.from(CLOCK.instant())));
        assertCounts(0, 0, 0);
    }

    private Outcome independentCreate(CreateReservation request, String key) {
        try {
            return new Outcome(store(CLOCK, Duration.ofSeconds(2)).create(request, key).id(), null);
        } catch (ReservationPersistenceException exception) {
            return new Outcome(null, exception.code());
        }
    }

    private List<Outcome> race(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return first.call(); });
            var right = executor.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private CreateReservation request() {
        return new CreateReservation(user, room, seat, new TimeRange(at("09:00:00"), at("11:00:00")), 7);
    }

    private static ReservationStore store(Clock clock, Duration timeout) {
        return new ReservationStore(dataSource, new ReservationPolicy(Duration.ofHours(24), Duration.ofHours(4), clock), clock, timeout);
    }

    private static Instant at(String time) {
        return OffsetDateTime.parse("2026-10-28T" + time + "+08:00").toInstant();
    }

    private static void assertCounts(int reservations, int requests, int events) {
        assertEquals(reservations, jdbc.queryForObject("select count(*) from m1.reservations", Integer.class));
        assertEquals(requests, jdbc.queryForObject("select count(*) from m1.reservation_requests", Integer.class));
        assertEquals(events, jdbc.queryForObject("select count(*) from m1.outbox_events", Integer.class));
    }

    private static void assertCode(ReservationPersistenceException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(ReservationPersistenceException.class, operation::run).code());
    }

    private record Outcome(UUID id, ReservationPersistenceException.Code error) {}
}
