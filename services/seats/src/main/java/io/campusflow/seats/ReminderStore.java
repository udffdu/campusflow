package io.campusflow.seats;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable CF-M1-008/009 scheduling and delivery leases. No network call runs in these transactions. */
public final class ReminderStore {
    public static final String START="RESERVATION_START_REMINDER";
    public static final String LATE="RESERVATION_LATE_REMINDER";
    public static final String NO_SHOW="RESERVATION_NO_SHOW";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReminderStore(DataSource source,Clock clock){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.clock=Objects.requireNonNull(clock);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private record Candidate(UUID reservationId,UUID userId,UUID roomId,UUID seatId,Instant startAt,Instant endAt){}
    public record Delivery(UUID eventId,String eventType,UUID reservationId,UUID userId,UUID roomId,UUID seatId,
                           Instant startAt,Instant endAt,int attempts){}

    public int scheduleDue(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("reminder scan limit");
        Integer result=transaction.execute(ignored->{
            Instant now=clock.instant();int start=schedule(START,jdbc.query("""
                    select id,user_id,room_id,seat_id,start_at,end_at from m1.reservations
                    where status='CONFIRMED' and start_at>? and start_at<=?
                    order by start_at,id limit ? for update skip locked
                    """,(row,index)->candidate(row),Timestamp.from(now),Timestamp.from(now.plus(Duration.ofMinutes(15))),limit));
            int remaining=Math.max(0,limit-start);
            int late=remaining==0?0:schedule(LATE,jdbc.query("""
                    select id,user_id,room_id,seat_id,start_at,end_at from m1.reservations
                    where status='CONFIRMED' and start_at>? and start_at<=?
                    order by start_at,id limit ? for update skip locked
                    """,(row,index)->candidate(row),Timestamp.from(now.minus(Duration.ofMinutes(15))),
                    Timestamp.from(now.minus(Duration.ofMinutes(10))),remaining));
            return start+late;
        });
        return result==null?0:result;
    }
    private Candidate candidate(java.sql.ResultSet row)throws java.sql.SQLException{
        return new Candidate(row.getObject("id",UUID.class),row.getObject("user_id",UUID.class),row.getObject("room_id",UUID.class),
                row.getObject("seat_id",UUID.class),row.getTimestamp("start_at").toInstant(),row.getTimestamp("end_at").toInstant());
    }
    private int schedule(String type,List<Candidate> candidates){
        int inserted=0;Instant now=clock.instant();
        for(var value:candidates)inserted+=jdbc.update("""
                insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at,next_attempt_at)
                values (?,?,?,jsonb_build_object('reservationId',cast(? as text),'userId',cast(? as text),
                  'roomId',cast(? as text),'seatId',cast(? as text),'startAt',cast(? as text),'endAt',cast(? as text)),?,?)
                on conflict(reservation_id,event_type) do nothing
                """,UUID.randomUUID(),value.reservationId(),type,value.reservationId().toString(),value.userId().toString(),
                value.roomId().toString(),value.seatId().toString(),value.startAt().toString(),value.endAt().toString(),
                Timestamp.from(now),Timestamp.from(now));
        return inserted;
    }
    public List<Delivery> claimDue(int limit,Duration lease){
        if(limit<1||limit>100||lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("reminder claim settings");
        List<Delivery> result=transaction.execute(ignored->{
            Instant now=clock.instant();
            var rows=jdbc.query("""
                    select o.event_id,o.event_type,o.reservation_id,r.user_id,r.room_id,r.seat_id,r.start_at,r.end_at,o.attempts
                    from m1.outbox_events o join m1.reservations r on r.id=o.reservation_id
                    where o.event_type in ('RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER','RESERVATION_NO_SHOW')
                      and ((o.status='PENDING' and o.next_attempt_at<=?)
                           or (o.status='DELIVERING' and o.claimed_until<=?))
                    order by o.next_attempt_at,o.created_at,o.event_id limit ? for update of o skip locked
                    """,(row,index)->new Delivery(row.getObject(1,UUID.class),row.getString(2),row.getObject(3,UUID.class),
                    row.getObject(4,UUID.class),row.getObject(5,UUID.class),row.getObject(6,UUID.class),
                    row.getTimestamp(7).toInstant(),row.getTimestamp(8).toInstant(),row.getInt(9)+1),
                    Timestamp.from(now),Timestamp.from(now),limit);
            for(var row:rows)jdbc.update("update m1.outbox_events set status='DELIVERING',attempts=?,claimed_until=?,last_error=null where event_id=?",
                    row.attempts(),Timestamp.from(now.plus(lease)),row.eventId());
            return List.copyOf(rows);
        });
        return result==null?List.of():result;
    }
    /** Re-checks domain state after claiming so an obsolete reminder is never deliberately dispatched. */
    public boolean prepareDelivery(Delivery delivery){
        Boolean result=transaction.execute(ignored->{
            var row=jdbc.query("""
                    select o.event_type,o.status,r.status as reservation_status
                    from m1.outbox_events o join m1.reservations r on r.id=o.reservation_id
                    where o.event_id=? for update of o
                    """,(rs,index)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},delivery.eventId())
                    .stream().findFirst().orElse(null);
            if(row==null||!"DELIVERING".equals(row[1]))return false;
            boolean eligible=(NO_SHOW.equals(row[0])&&"NO_SHOW".equals(row[2]))
                    ||((START.equals(row[0])||LATE.equals(row[0]))&&"CONFIRMED".equals(row[2]));
            if(!eligible)jdbc.update("""
                    update m1.outbox_events
                    set status='SUPERSEDED',claimed_until=null,last_error=null,superseded_at=?
                    where event_id=? and status='DELIVERING'
                    """,Timestamp.from(clock.instant()),delivery.eventId());
            return eligible;
        });
        return Boolean.TRUE.equals(result);
    }
    public void delivered(UUID eventId){transaction.executeWithoutResult(ignored->jdbc.update("""
            update m1.outbox_events set status='DELIVERED',claimed_until=null,delivered_at=?,last_error=null
            where event_id=? and status='DELIVERING'
            """,Timestamp.from(clock.instant()),eventId));}
    public void retry(Delivery delivery,String reason,Duration base,Duration maximum){
        if(base.isNegative()||base.isZero()||maximum.compareTo(base)<0)throw new IllegalArgumentException("reminder retry settings");
        long factor=1L<<Math.min(20,Math.max(0,delivery.attempts()-1));
        Duration delay=base.multipliedBy(factor);if(delay.compareTo(maximum)>0)delay=maximum;
        String safe=Set.of("D_UNAVAILABLE","D_REJECTED","DELIVERY_UNKNOWN").contains(reason)?reason:"DELIVERY_UNKNOWN";
        Duration finalDelay=delay;
        transaction.executeWithoutResult(ignored->jdbc.update("""
                update m1.outbox_events set status='PENDING',claimed_until=null,next_attempt_at=?,last_error=?
                where event_id=? and status='DELIVERING'
                """,Timestamp.from(clock.instant().plus(finalDelay)),safe,delivery.eventId()));
    }
}
