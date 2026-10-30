package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Timeout(20)
class ReservationCheckInStoreTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Instant START = Instant.parse("2026-10-30T01:00:00Z");
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private final UUID user = UUID.randomUUID(), room = UUID.randomUUID(), seat = UUID.randomUUID();
    private ReservationRecord reservation;

    @BeforeAll static void database() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        dataSource = postgres.getPostgresDatabase(); jdbc = new JdbcTemplate(dataSource);
        assertEquals(2, Flyway.configure().dataSource(dataSource).schemas("m1").defaultSchema("m1").load().migrate().migrationsExecuted);
    }
    @AfterAll static void close() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void reset() {
        jdbc.execute("truncate m1.checkin_rate_limits,m1.reservation_transitions,m1.outbox_events,m1.reservation_requests,m1.reservations");
        reservation = store(Clock.fixed(START.minusSeconds(3600), ZoneOffset.UTC)).create(
                new CreateReservation(user, room, seat, new TimeRange(START, START.plusSeconds(3600)), 1), "check-in");
    }

    @Test void successfulCheckInWritesStatusTransitionAndEventAtomically() {
        var clock=Clock.fixed(START,ZoneOffset.UTC);var codes=codes(clock);
        var result=store(clock).checkIn(user,reservation.id(),codes.current(room).code(),codes,()->{});
        assertFalse(result.idempotent());assertEquals(ReservationStatus.CHECKED_IN,result.reservation().status());assertEquals(2,result.reservation().version());
        assertEquals(1,count("reservation_transitions"));assertEquals(2,count("outbox_events"));assertEquals(0,count("checkin_rate_limits"));
        assertEquals("USER_CHECK_IN",jdbc.queryForObject("select reason from m1.reservation_transitions",String.class));
        assertEquals("RESERVATION_CHECKED_IN",jdbc.queryForObject("select event_type from m1.outbox_events where event_type<>'RESERVATION_CREATED'",String.class));
        assertNull(jdbc.queryForObject("select payload->>'code' from m1.outbox_events where event_type='RESERVATION_CHECKED_IN'",String.class));
    }

    @Test void repeatedCheckInIsIdempotentAndDoesNotNeedCodeAgain() {
        var atStart=Clock.fixed(START,ZoneOffset.UTC);var codes=codes(atStart);
        store(atStart).checkIn(user,reservation.id(),codes.current(room).code(),codes,()->{});
        var later=Clock.fixed(START.plus(Duration.ofHours(2)),ZoneOffset.UTC);
        var replay=store(later).checkIn(user,reservation.id(),"bad",codes(later),()->{});
        assertTrue(replay.idempotent());assertEquals(2,replay.reservation().version());assertEquals(1,count("reservation_transitions"));assertEquals(2,count("outbox_events"));
    }

    @Test void checkInDoesNotApplyReservationTimeWindow() {
        UUID laterUser=UUID.randomUUID(),laterSeat=UUID.randomUUID();
        var laterReservation=store(Clock.fixed(START.minusSeconds(3600),ZoneOffset.UTC)).create(
                new CreateReservation(laterUser,room,laterSeat,new TimeRange(START,START.plusSeconds(3600)),1),"later-check-in");
        var before=Clock.fixed(START.minusNanos(1),ZoneOffset.UTC);
        assertEquals(ReservationStatus.CHECKED_IN,store(before).checkIn(user,reservation.id(),codes(before).current(room).code(),codes(before),()->{}).reservation().status());
        var later=Clock.fixed(START.plus(Duration.ofHours(2)),ZoneOffset.UTC);
        assertEquals(ReservationStatus.CHECKED_IN,store(later).checkIn(laterUser,laterReservation.id(),codes(later).current(room).code(),codes(later),()->{}).reservation().status());
    }

    @Test void terminalStateCannotBeCheckedIn() {
        jdbc.update("update m1.reservations set status='NO_SHOW' where id=?",reservation.id());
        var valid=Clock.fixed(START,ZoneOffset.UTC);
        assertCode(CheckInException.Code.CHECK_IN_STATE_CONFLICT,()->store(valid).checkIn(user,reservation.id(),"000000",codes(valid),()->{}));
        assertEquals(1,count("outbox_events"));assertEquals(0,count("reservation_transitions"));
    }

    @Test void usageTimelineAndLeaveSeatArePersistedAndIdempotent() {
        var checkedAt=Clock.fixed(START.minus(Duration.ofHours(1)),ZoneOffset.UTC);var checkCodes=codes(checkedAt);
        store(checkedAt).checkIn(user,reservation.id(),checkCodes.current(room).code(),checkCodes,()->{});
        var active=store(checkedAt).usageForUser(user,reservation.id());
        assertEquals(START.minus(Duration.ofHours(1)),active.checkedInAt());assertNull(active.completedAt());
        var leftAt=Clock.fixed(START.plus(Duration.ofMinutes(30)),ZoneOffset.UTC);
        var left=store(leftAt).leaveSeat(user,reservation.id(),()->{});
        assertFalse(left.idempotent());assertEquals(ReservationStatus.COMPLETED,left.reservation().status());assertEquals(3,left.reservation().version());
        var completed=store(leftAt).usageForUser(user,reservation.id());assertEquals(START.plus(Duration.ofMinutes(30)),completed.completedAt());
        var replay=store(leftAt).leaveSeat(user,reservation.id(),()->{});assertTrue(replay.idempotent());
        assertEquals(2,count("reservation_transitions"));assertEquals(3,count("outbox_events"));
        assertEquals("USER_LEAVE_SEAT",jdbc.queryForObject("select reason from m1.reservation_transitions where to_status='COMPLETED'",String.class));
    }

    @Test void leaveSeatRequiresAnOwnedActiveUsage() {
        assertCode(CheckInException.Code.LEAVE_SEAT_STATE_CONFLICT,()->store(Clock.fixed(START,ZoneOffset.UTC)).leaveSeat(user,reservation.id(),()->{}));
        assertCode(CheckInException.Code.RESERVATION_NOT_FOUND,()->store(Clock.fixed(START,ZoneOffset.UTC)).leaveSeat(UUID.randomUUID(),reservation.id(),()->{}));
    }

    @Test void wrongRoomOrDateCodeIsRejectedAndFailuresAreDurablyLimited() {
        var clock=Clock.fixed(START,ZoneOffset.UTC);var codes=codes(clock);
        String wrongRoom=codes.current(UUID.randomUUID()).code();
        assertCode(CheckInException.Code.CHECK_IN_CODE_INVALID,()->store(clock).checkIn(user,reservation.id(),wrongRoom,codes,()->{}));
        String previousDay=codes.codeFor(room,LocalDate.now(clock.withZone(DailyCheckInCode.CAMPUS_ZONE)).minusDays(1));
        for(int i=0;i<3;i++)assertCode(CheckInException.Code.CHECK_IN_CODE_INVALID,()->store(clock).checkIn(user,reservation.id(),previousDay,codes,()->{}));
        assertCode(CheckInException.Code.CHECK_IN_RATE_LIMITED,()->store(clock).checkIn(user,reservation.id(),previousDay,codes,()->{}));
        assertCode(CheckInException.Code.CHECK_IN_RATE_LIMITED,()->store(clock).checkIn(user,reservation.id(),codes.current(room).code(),codes,()->{}));
        assertEquals(5,jdbc.queryForObject("select failures from m1.checkin_rate_limits",Integer.class));
        assertEquals("CONFIRMED",jdbc.queryForObject("select status from m1.reservations",String.class));
    }

    @Test void ownershipAndLockedAuthorizationAreRecheckedWithoutLeakingForeignRows() {
        var clock=Clock.fixed(START,ZoneOffset.UTC);var codes=codes(clock);String code=codes.current(room).code();
        assertCode(CheckInException.Code.RESERVATION_NOT_FOUND,()->store(clock).checkIn(UUID.randomUUID(),reservation.id(),code,codes,()->{}));
        assertThrows(SecurityException.class,()->store(clock).checkIn(user,reservation.id(),code,codes,()->{throw new SecurityException();}));
        assertEquals("CONFIRMED",jdbc.queryForObject("select status from m1.reservations",String.class));assertEquals(0,count("reservation_transitions"));
    }

    @Test void eventFailureRollsBackStatusAndTransition() {
        jdbc.execute("create function m1.reject_checkin_event() returns trigger language plpgsql as $$ begin if new.event_type='RESERVATION_CHECKED_IN' then raise exception 'injected'; end if; return new; end; $$");
        jdbc.execute("create trigger reject_checkin_event before insert on m1.outbox_events for each row execute function m1.reject_checkin_event()");
        try {
            var clock=Clock.fixed(START,ZoneOffset.UTC);var codes=codes(clock);
            assertThrows(ReservationPersistenceException.class,()->store(clock).checkIn(user,reservation.id(),codes.current(room).code(),codes,()->{}));
            assertEquals("CONFIRMED",jdbc.queryForObject("select status from m1.reservations",String.class));assertEquals(0,count("reservation_transitions"));assertEquals(1,count("outbox_events"));
        } finally {jdbc.execute("drop trigger reject_checkin_event on m1.outbox_events");jdbc.execute("drop function m1.reject_checkin_event()");}
    }

    @Test void concurrentRetriesProduceOneTransitionAndOneLifecycleEvent() throws Exception {
        var clock=Clock.fixed(START,ZoneOffset.UTC);String code=codes(clock).current(room).code();
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<ReservationStore.CheckInResult> action=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return store(clock).checkIn(user,reservation.id(),code,codes(clock),()->{});};
            var a=pool.submit(action);var b=pool.submit(action);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertEquals(Set.of(false,true),Set.of(a.get(10,TimeUnit.SECONDS).idempotent(),b.get(10,TimeUnit.SECONDS).idempotent()));
            assertEquals(1,count("reservation_transitions"));assertEquals(2,count("outbox_events"));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }

    private ReservationStore store(Clock clock){return new ReservationStore(dataSource,new ReservationPolicy(Duration.ofDays(2),Duration.ofHours(4),clock),clock,Duration.ofSeconds(2));}
    private DailyCheckInCode codes(Clock clock){return new DailyCheckInCode(SECRET,clock);}
    private int count(String table){return jdbc.queryForObject("select count(*) from m1."+table,Integer.class);}
    private static void assertCode(CheckInException.Code expected,Runnable action){assertEquals(expected,assertThrows(CheckInException.class,action::run).code());}
}
