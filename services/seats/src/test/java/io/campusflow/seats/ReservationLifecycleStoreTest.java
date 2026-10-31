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

@Timeout(25)
class ReservationLifecycleStoreTest {
    private static final Instant NOW=Instant.parse("2026-10-31T08:00:00Z");
    private static EmbeddedPostgres postgres;
    private static DataSource source;
    private static JdbcTemplate jdbc;

    @BeforeAll static void database()throws Exception{
        postgres=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
        source=postgres.getPostgresDatabase();jdbc=new JdbcTemplate(source);
        assertEquals(4,Flyway.configure().dataSource(source).schemas("m1").defaultSchema("m1").load().migrate().migrationsExecuted);
    }
    @AfterAll static void close()throws Exception{if(postgres!=null)postgres.close();}
    @BeforeEach void reset(){jdbc.execute("truncate m1.reservation_violations,m1.checkin_rate_limits,m1.reservation_transitions,m1.outbox_events,m1.reservation_requests,m1.reservations");}

    @Test void exactDeadlineAtomicallyCancelsReleasesAndRecordsOneViolation(){
        var row=reservation(NOW.minus(Duration.ofMinutes(15)),NOW.plus(Duration.ofHours(2)),"CONFIRMED");
        var store=reservations(NOW);
        assertEquals(ReservationStore.LifecycleResult.TRANSITIONED,store.markNoShowIfDue(row.id()));
        assertEquals("NO_SHOW",value("select status from m1.reservations where id=?",String.class,row.id()));
        assertEquals(2L,value("select version from m1.reservations where id=?",Long.class,row.id()));
        assertEquals("CHECK_IN_TIMEOUT",value("select reason from m1.reservation_transitions where reservation_id=?",String.class,row.id()));
        assertEquals(1,count("reservation_violations","reservation_id",row.id()));
        assertEquals("NO_SHOW",value("select reason from m1.reservation_violations where reservation_id=?",String.class,row.id()));
        assertEquals(2L,value("select reservation_version from m1.reservation_violations where reservation_id=?",Long.class,row.id()));
        assertEquals("RESERVATION_NO_SHOW",value("select event_type from m1.outbox_events where reservation_id=?",String.class,row.id()));

        var replacement=store.create(new CreateReservation(UUID.randomUUID(),row.roomId(),row.seatId(),
                new TimeRange(NOW.plus(Duration.ofHours(1)),NOW.plus(Duration.ofHours(2))),1),"replacement");
        assertEquals(ReservationStatus.CONFIRMED,replacement.status());
        assertEquals(ReservationStore.LifecycleResult.NOT_APPLICABLE,store.markNoShowIfDue(row.id()));
        assertEquals(1,count("reservation_violations","reservation_id",row.id()));
        assertEquals(1,count("outbox_events","event_type","RESERVATION_NO_SHOW"));
    }

    @Test void aNanosecondBeforeDeadlineIsNotDueAndTerminalStatesNeverCreateViolations(){
        var pending=reservation(NOW.minus(Duration.ofMinutes(15)).plusMillis(1),NOW.plusSeconds(1),"CONFIRMED");
        var checkedIn=reservation(NOW.minus(Duration.ofHours(1)),NOW.plus(Duration.ofMinutes(1)),"CHECKED_IN");
        var disrupted=reservation(NOW.minus(Duration.ofHours(1)),NOW.plus(Duration.ofMinutes(1)),"DISRUPTED");
        var store=reservations(NOW);
        assertEquals(ReservationStore.LifecycleResult.NOT_DUE,store.markNoShowIfDue(pending.id()));
        assertEquals(ReservationStore.LifecycleResult.NOT_APPLICABLE,store.markNoShowIfDue(checkedIn.id()));
        assertEquals(ReservationStore.LifecycleResult.NOT_APPLICABLE,store.markNoShowIfDue(disrupted.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from m1.reservation_violations",Integer.class));
    }

    @Test void twoRealConnectionsRaceToOneTransitionAndOneViolation()throws Exception{
        var row=reservation(NOW.minus(Duration.ofHours(1)),NOW.plus(Duration.ofMinutes(1)),"CONFIRMED");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            Callable<ReservationStore.LifecycleResult> task=()->{
                ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));
                return reservations(NOW).markNoShowIfDue(row.id());
            };
            var first=pool.submit(task);var second=pool.submit(task);
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertEquals(Set.of(ReservationStore.LifecycleResult.TRANSITIONED,ReservationStore.LifecycleResult.NOT_APPLICABLE),
                    Set.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)));
        }
        assertEquals(1,count("reservation_transitions","reservation_id",row.id()));
        assertEquals(1,count("reservation_violations","reservation_id",row.id()));
        assertEquals(1,count("outbox_events","event_type","RESERVATION_NO_SHOW"));
    }

    @Test void outboxFailureRollsBackStatusTransitionAndViolation(){
        var row=reservation(NOW.minus(Duration.ofHours(1)),NOW.plus(Duration.ofMinutes(1)),"CONFIRMED");
        jdbc.execute("create function m1.reject_no_show_event() returns trigger language plpgsql as $$ begin if new.event_type='RESERVATION_NO_SHOW' then raise exception 'injected'; end if; return new; end; $$");
        jdbc.execute("create trigger reject_no_show_event before insert on m1.outbox_events for each row execute function m1.reject_no_show_event()");
        try{
            assertThrows(ReservationPersistenceException.class,()->reservations(NOW).markNoShowIfDue(row.id()));
            assertEquals("CONFIRMED",value("select status from m1.reservations where id=?",String.class,row.id()));
            assertEquals(1L,value("select version from m1.reservations where id=?",Long.class,row.id()));
            assertEquals(0,count("reservation_transitions","reservation_id",row.id()));
            assertEquals(0,count("reservation_violations","reservation_id",row.id()));
        }finally{
            jdbc.execute("drop trigger reject_no_show_event on m1.outbox_events");
            jdbc.execute("drop function m1.reject_no_show_event()");
        }
    }

    @Test void lifecycleCycleAlsoCompletesCheckedInReservationsAtExactEnd(){
        var noShow=reservation(NOW.minus(Duration.ofMinutes(15)),NOW.plus(Duration.ofMinutes(1)),"CONFIRMED");
        var complete=reservation(NOW.minus(Duration.ofHours(1)),NOW,"CHECKED_IN");
        var notYet=reservation(NOW.minus(Duration.ofHours(1)),NOW.plusMillis(1),"CHECKED_IN");
        var cycle=new ReservationLifecycleStore(source,reservations(NOW),Clock.fixed(NOW,ZoneOffset.UTC)).processDue(20);
        assertEquals(1,cycle.noShows());assertEquals(1,cycle.completed());assertEquals(0,cycle.failures());
        assertEquals("NO_SHOW",value("select status from m1.reservations where id=?",String.class,noShow.id()));
        assertEquals("COMPLETED",value("select status from m1.reservations where id=?",String.class,complete.id()));
        assertEquals("CHECKED_IN",value("select status from m1.reservations where id=?",String.class,notYet.id()));
        assertEquals("RESERVATION_END",value("select reason from m1.reservation_transitions where reservation_id=?",String.class,complete.id()));
        var replay=new ReservationLifecycleStore(source,reservations(NOW),Clock.fixed(NOW,ZoneOffset.UTC)).processDue(20);
        assertEquals(0,replay.noShows());assertEquals(0,replay.completed());assertEquals(0,replay.failures());
    }

    private ReservationStore reservations(Instant at){
        Clock clock=Clock.fixed(at,ZoneOffset.UTC);
        return new ReservationStore(source,new ReservationPolicy(Duration.ofDays(2),Duration.ofHours(4),clock),clock,Duration.ofSeconds(3));
    }
    private Row reservation(Instant start,Instant end,String status){
        var row=new Row(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        jdbc.update("""
                insert into m1.reservations(id,user_id,room_id,seat_id,start_at,end_at,status,source,resource_version,version,created_at,updated_at)
                values (?,?,?,?,?,?,?,'M1',1,1,?,?)
                """,row.id(),row.userId(),row.roomId(),row.seatId(),Timestamp.from(start),Timestamp.from(end),status,
                Timestamp.from(NOW.minus(Duration.ofDays(1))),Timestamp.from(NOW.minus(Duration.ofDays(1))));
        return row;
    }
    private int count(String table,String column,Object value){return value("select count(*) from m1."+table+" where "+column+"=?",Integer.class,value);}
    private <T>T value(String sql,Class<T> type,Object...args){return jdbc.queryForObject(sql,type,args);}
    private record Row(UUID id,UUID userId,UUID roomId,UUID seatId){}
}
