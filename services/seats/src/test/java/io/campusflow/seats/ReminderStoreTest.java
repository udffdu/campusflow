package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Timeout(20)
class ReminderStoreTest {
    private static final Instant NOW=Instant.parse("2026-10-30T08:00:00Z");
    private static EmbeddedPostgres postgres;
    private static DataSource source;
    private static JdbcTemplate jdbc;
    @BeforeAll static void database()throws Exception{
        postgres=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
        source=postgres.getPostgresDatabase();jdbc=new JdbcTemplate(source);
        assertEquals(5,Flyway.configure().dataSource(source).schemas("m1").defaultSchema("m1").load().migrate().migrationsExecuted);
    }
    @AfterAll static void close()throws Exception{if(postgres!=null)postgres.close();}
    @BeforeEach void reset(){jdbc.execute("truncate m1.reservation_violations,m1.checkin_rate_limits,m1.reservation_transitions,m1.outbox_events,m1.reservation_requests,m1.reservations");}

    @Test void exactBoundariesAndStatusesProduceOnlyTheTwoRequiredEvents(){
        UUID exact=reservation(NOW.plus(Duration.ofMinutes(15)),"CONFIRMED");
        reservation(NOW.plus(Duration.ofMinutes(15)).plusSeconds(1),"CONFIRMED");
        reservation(NOW,"CONFIRMED");
        UUID late=reservation(NOW.minus(Duration.ofMinutes(10)),"CONFIRMED");
        reservation(NOW.minus(Duration.ofMinutes(10)),"CHECKED_IN");
        var store=store(NOW);assertEquals(2,store.scheduleDue(100));assertEquals(0,store.scheduleDue(100));
        assertEquals(Set.of(exact,late),new HashSet<>(jdbc.queryForList("select reservation_id from m1.outbox_events",UUID.class)));
        assertEquals(Set.of(ReminderStore.START,ReminderStore.LATE),new HashSet<>(jdbc.queryForList("select event_type from m1.outbox_events",String.class)));
    }
    @Test void bookingInsideFifteenMinutesIsScheduledImmediately(){
        UUID id=reservation(NOW.plus(Duration.ofMinutes(3)),"CONFIRMED");
        assertEquals(1,store(NOW).scheduleDue(10));
        assertEquals(id,jdbc.queryForObject("select reservation_id from m1.outbox_events where event_type=?",UUID.class,ReminderStore.START));
    }
    @Test void concurrentScannersStillCreateOneBusinessEvent()throws Exception{
        reservation(NOW.plus(Duration.ofMinutes(10)),"CONFIRMED");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            Callable<Integer> task=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return store(NOW).scheduleDue(10);};
            var a=pool.submit(task);var b=pool.submit(task);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertEquals(1,a.get()+b.get());
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from m1.outbox_events",Integer.class));
    }
    @Test void deliveryLeaseRecoversAfterCrashAndRetryUsesBackoff(){
        reservation(NOW.plus(Duration.ofMinutes(10)),"CONFIRMED");var first=store(NOW);first.scheduleDue(10);
        var claim=first.claimDue(10,Duration.ofMinutes(1)).getFirst();assertEquals(1,claim.attempts());assertTrue(first.claimDue(10,Duration.ofMinutes(1)).isEmpty());
        var recovered=store(NOW.plus(Duration.ofMinutes(2))).claimDue(10,Duration.ofMinutes(1)).getFirst();assertEquals(2,recovered.attempts());
        store(NOW.plus(Duration.ofMinutes(2))).retry(recovered,"D_UNAVAILABLE",Duration.ofSeconds(5),Duration.ofMinutes(5));
        assertTrue(store(NOW.plusSeconds(124)).claimDue(10,Duration.ofMinutes(1)).isEmpty());
        var retried=store(NOW.plusSeconds(130)).claimDue(10,Duration.ofMinutes(1)).getFirst();assertEquals(3,retried.attempts());
        store(NOW.plusSeconds(130)).delivered(retried.eventId());
        assertEquals("DELIVERED",jdbc.queryForObject("select status from m1.outbox_events",String.class));
        assertNotNull(jdbc.queryForObject("select delivered_at from m1.outbox_events",Timestamp.class));
    }
    @Test void noShowNotificationIsDeliverableAndStaleReminderIsSuperseded(){
        UUID noShow=reservation(NOW.minus(Duration.ofHours(1)),"NO_SHOW");
        jdbc.update("""
                insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at,next_attempt_at)
                values (?,?,?,jsonb_build_object('reservationId',cast(? as text)),?,?)
                """,UUID.randomUUID(),noShow,ReminderStore.NO_SHOW,noShow.toString(),Timestamp.from(NOW),Timestamp.from(NOW));
        var store=store(NOW);
        var violationNotice=store.claimDue(10,Duration.ofMinutes(1)).getFirst();
        assertEquals(ReminderStore.NO_SHOW,violationNotice.eventType());
        assertTrue(store.prepareDelivery(violationNotice));
        store.delivered(violationNotice.eventId());

        UUID stale=reservation(NOW.plus(Duration.ofMinutes(10)),"CONFIRMED");
        assertEquals(1,store.scheduleDue(10));
        var reminder=store.claimDue(10,Duration.ofMinutes(1)).getFirst();
        jdbc.update("update m1.reservations set status='NO_SHOW' where id=?",stale);
        assertFalse(store.prepareDelivery(reminder));
        assertEquals("SUPERSEDED",jdbc.queryForObject("select status from m1.outbox_events where event_id=?",String.class,reminder.eventId()));
        assertNotNull(jdbc.queryForObject("select superseded_at from m1.outbox_events where event_id=?",Timestamp.class,reminder.eventId()));
    }
    private ReminderStore store(Instant at){return new ReminderStore(source,Clock.fixed(at,ZoneOffset.UTC));}
    private UUID reservation(Instant start,String status){
        UUID id=UUID.randomUUID();Instant end=start.plus(Duration.ofHours(1));
        jdbc.update("""
                insert into m1.reservations(id,user_id,room_id,seat_id,start_at,end_at,status,source,resource_version,version,created_at,updated_at)
                values (?,?,?,?,?,?,?,'M1',1,1,?,?)
                """,id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Timestamp.from(start),Timestamp.from(end),status,Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW.minusSeconds(60)));
        return id;
    }
}
