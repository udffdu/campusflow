package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Timeout(25)
class ReservationOperationsStoreTest {
    private static final Instant NOW=Instant.parse("2026-10-31T11:35:00Z");
    private static final Instant FROM=Instant.parse("2026-10-31T08:00:00Z"),TO=Instant.parse("2026-10-31T16:00:00Z");
    private static EmbeddedPostgres postgres;
    private static DataSource source;
    private static JdbcTemplate jdbc;
    private final UUID roomA=UUID.randomUUID(),roomB=UUID.randomUUID(),outside=UUID.randomUUID();
    private final UUID seatA=UUID.randomUUID(),seatB=UUID.randomUUID(),seatC=UUID.randomUUID();

    @BeforeAll static void database()throws Exception{
        postgres=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
        source=postgres.getPostgresDatabase();jdbc=new JdbcTemplate(source);
        assertEquals(5,Flyway.configure().dataSource(source).schemas("m1").defaultSchema("m1").load().migrate().migrationsExecuted);
    }
    @AfterAll static void close()throws Exception{if(postgres!=null)postgres.close();}
    @BeforeEach void reset(){
        jdbc.execute("truncate m1.reservation_violations,m1.checkin_rate_limits,m1.reservation_transitions,m1.outbox_events,m1.reservation_requests,m1.reservations");
        row(roomA,seatA,"08:00","10:00",ReservationStatus.COMPLETED,"08:05","09:35");
        row(roomA,seatB,"10:00","11:00",ReservationStatus.NO_SHOW,null,null);
        row(roomB,seatC,"11:00","12:00",ReservationStatus.CHECKED_IN,"11:05",null);
        row(roomB,seatC,"13:00","14:00",ReservationStatus.CONFIRMED,null,null);
        row(roomA,seatA,"09:00","10:00",ReservationStatus.DISRUPTED,"09:05",null);
        row(outside,UUID.randomUUID(),"09:00","10:00",ReservationStatus.COMPLETED,"09:00","09:30");
    }

    @Test void scopedSnapshotUsesStableCursorAndDocumentedMetrics(){
        var store=store();var filter=filter(null,null,null);
        var first=store.query(filter,null,2);
        assertEquals(5,first.summary().total());
        assertEquals(2,first.items().size());assertNotNull(first.next());
        var second=store.query(filter,first.next(),2);var third=store.query(filter,second.next(),2);
        var ids=new HashSet<UUID>();first.items().forEach(row->assertTrue(ids.add(row.id())));
        second.items().forEach(row->assertTrue(ids.add(row.id())));third.items().forEach(row->assertTrue(ids.add(row.id())));
        assertEquals(5,ids.size());assertNull(third.next());
        for(var page:List.of(first,second,third))assertEquals(first.summary(),page.summary());

        var summary=first.summary();
        assertEquals(2,summary.checkedIn());assertEquals(1,summary.violations());assertEquals(3,summary.eligible());
        assertEquals(2d/3d,summary.checkInRate(),0.000001);
        assertEquals(14_400,summary.reservedDurationSeconds());assertEquals(7_200,summary.actualUsageSeconds());
        assertEquals(.5,summary.utilizationRate(),0.000001);
        assertEquals(List.of(new ReservationOperationsStore.Count(roomA,3),new ReservationOperationsStore.Count(roomB,2)),summary.byRoom());
        assertEquals(1L,summary.byStatus().get(ReservationStatus.NO_SHOW));
    }

    @Test void filtersAffectBothRowsAndMetricsWithoutLeakingAnotherRoom(){
        var noShows=store().query(filter(roomA,null,ReservationStatus.NO_SHOW),null,20);
        assertEquals(1,noShows.summary().total());assertEquals(0,noShows.summary().checkedIn());assertEquals(1,noShows.summary().violations());
        assertEquals(0d,noShows.summary().checkInRate());assertNull(noShows.summary().utilizationRate());
        assertEquals(roomA,noShows.items().getFirst().roomId());
        assertThrows(SecurityException.class,()->new ReservationOperationsStore.Filter(FROM,TO,Set.of(roomA,roomB),outside,null,null));
        var empty=store().query(new ReservationOperationsStore.Filter(FROM,TO,Set.of(),null,null,null),null,20);
        assertEquals(0,empty.summary().total());assertNull(empty.summary().checkInRate());assertTrue(empty.items().isEmpty());
    }

    private ReservationOperationsStore store(){return new ReservationOperationsStore(source,Clock.fixed(NOW,ZoneOffset.UTC));}
    private ReservationOperationsStore.Filter filter(UUID room,UUID seat,ReservationStatus status){
        return new ReservationOperationsStore.Filter(FROM,TO,Set.of(roomA,roomB),room,seat,status);
    }
    private void row(UUID room,UUID seat,String start,String end,ReservationStatus status,String checked,String completed){
        UUID id=UUID.randomUUID(),user=UUID.randomUUID();Instant startAt=time(start),endAt=time(end);
        jdbc.update("""
                insert into m1.reservations(id,user_id,room_id,seat_id,start_at,end_at,status,source,resource_version,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,'M1',1,?,?,?)
                """,id,user,room,seat,Timestamp.from(startAt),Timestamp.from(endAt),status.name(),
                completed!=null?3:checked!=null?2:1,Timestamp.from(startAt.minusSeconds(3600)),Timestamp.from(NOW));
        if(checked!=null)transition(id,1,"CONFIRMED","CHECKED_IN",time(checked));
        if(completed!=null)transition(id,2,"CHECKED_IN","COMPLETED",time(completed));
    }
    private void transition(UUID reservation,int index,String from,String to,Instant at){
        jdbc.update("""
                insert into m1.reservation_transitions(transition_id,reservation_id,from_status,to_status,reason,reservation_version,occurred_at)
                values(?,?,?,?,'TEST',?,?)
                """,UUID.randomUUID(),reservation,from,to,index+1,Timestamp.from(at));
    }
    private static Instant time(String hhmm){return Instant.parse("2026-10-31T"+hhmm+":00Z");}
}
