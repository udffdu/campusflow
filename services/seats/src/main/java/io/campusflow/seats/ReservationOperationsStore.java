package io.campusflow.seats;

import static io.campusflow.seats.ReservationPersistenceException.Code.STORAGE_UNAVAILABLE;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only, repeatable-read operational projection. It never returns reservation owners. */
public final class ReservationOperationsStore {
    public static final Duration MAX_RANGE=Duration.ofDays(31);
    public record Filter(Instant from,Instant to,Set<UUID> allowedRoomIds,UUID roomId,UUID seatId,ReservationStatus status) {
        public Filter {
            Objects.requireNonNull(from);Objects.requireNonNull(to);
            allowedRoomIds=Set.copyOf(Objects.requireNonNull(allowedRoomIds));
            if(!from.isBefore(to)||Duration.between(from,to).compareTo(MAX_RANGE)>0)throw new IllegalArgumentException("operations range");
            if(roomId!=null&&!allowedRoomIds.contains(roomId))throw new SecurityException("room outside operations scope");
        }
    }
    public record Position(Instant startAt,UUID id) { public Position{Objects.requireNonNull(startAt);Objects.requireNonNull(id);} }
    public record Entry(UUID id,UUID roomId,UUID seatId,Instant startAt,Instant endAt,ReservationStatus status,
                        Instant checkedInAt,Instant completedAt,long usageDurationSeconds,Instant createdAt,Instant updatedAt) {}
    public record Count(UUID id,long count) {}
    public record Summary(long total,Map<ReservationStatus,Long> byStatus,long checkedIn,long violations,
                          long eligible,Double checkInRate,long reservedDurationSeconds,long actualUsageSeconds,
                          Double utilizationRate,List<Count> byRoom,List<Count> bySeat) {}
    public record Result(List<Entry> items,Position next,Summary summary,Instant serverTime) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate reads;
    private final Clock clock;
    public ReservationOperationsStore(DataSource source,Clock clock){
        this.jdbc=new NamedParameterJdbcTemplate(Objects.requireNonNull(source));this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(new DataSourceTransactionManager(source));reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Result query(Filter filter,Position before,int size){
        Objects.requireNonNull(filter);if(size<1||size>100)throw new IllegalArgumentException("operations size");
        try{return Objects.requireNonNull(reads.execute(ignored->querySnapshot(filter,before,size)));}
        catch(DataAccessException exception){throw new ReservationPersistenceException(STORAGE_UNAVAILABLE,exception);}
    }

    private Result querySnapshot(Filter filter,Position before,int size){
        Instant serverTime=clock.instant();var parameters=new MapSqlParameterSource()
                .addValue("from",Timestamp.from(filter.from())).addValue("to",Timestamp.from(filter.to()));
        String predicate="r.start_at < :to and r.end_at > :from";
        if(filter.allowedRoomIds().isEmpty())predicate+=" and 1=0";
        else {predicate+=" and r.room_id in (:scopeRooms)";parameters.addValue("scopeRooms",filter.allowedRoomIds());}
        if(filter.roomId()!=null){predicate+=" and r.room_id=:room";parameters.addValue("room",filter.roomId());}
        if(filter.seatId()!=null){predicate+=" and r.seat_id=:seat";parameters.addValue("seat",filter.seatId());}
        if(filter.status()!=null){predicate+=" and r.status=:status";parameters.addValue("status",filter.status().name());}
        long total=number("select count(*) from m1.reservations r where "+predicate,parameters);
        EnumMap<ReservationStatus,Long> statuses=new EnumMap<>(ReservationStatus.class);
        for(var state:ReservationStatus.values())statuses.put(state,0L);
        jdbc.query("select r.status,count(*) total from m1.reservations r where "+predicate+" group by r.status",parameters,
                row->{statuses.put(ReservationStatus.valueOf(row.getString("status")),row.getLong("total"));});
        long checked=statuses.get(ReservationStatus.CHECKED_IN)+statuses.get(ReservationStatus.COMPLETED);
        long violations=statuses.get(ReservationStatus.NO_SHOW),eligible=checked+violations;
        Double rate=eligible==0?null:(double)checked/eligible;
        parameters.addValue("serverTime",Timestamp.from(serverTime));
        Map<String,Object> durationTotals=jdbc.queryForMap("""
                with lifecycle as (
                  select reservation_id,
                    max(occurred_at) filter (where to_status='CHECKED_IN') checked_in_at,
                    max(occurred_at) filter (where to_status='COMPLETED') completed_at
                  from m1.reservation_transitions group by reservation_id
                )
                select coalesce(sum(case when r.status in ('CONFIRMED','CHECKED_IN','COMPLETED')
                    then extract(epoch from (least(r.end_at,:to)-greatest(r.start_at,:from))) else 0 end),0)::bigint reserved_seconds,
                  coalesce(sum(case when r.status in ('CHECKED_IN','COMPLETED') and l.checked_in_at is not null
                    and least(coalesce(l.completed_at,:serverTime),r.end_at,:to)>greatest(l.checked_in_at,:from)
                    then extract(epoch from (least(coalesce(l.completed_at,:serverTime),r.end_at,:to)-greatest(l.checked_in_at,:from))) else 0 end),0)::bigint actual_seconds
                from m1.reservations r left join lifecycle l on l.reservation_id=r.id where
                """+predicate,parameters);
        long reserved=((Number)durationTotals.get("reserved_seconds")).longValue(),actual=((Number)durationTotals.get("actual_seconds")).longValue();
        Double utilization=reserved==0?null:(double)actual/reserved;
        List<Count> rooms=counts("room_id",predicate,parameters),seats=counts("seat_id",predicate,parameters);

        var pageParameters=new MapSqlParameterSource(parameters.getValues()).addValue("limit",size+1);
        String boundary="";
        if(before!=null){boundary=" and (r.start_at,r.id)<(:cursorStart,:cursorId)";
            pageParameters.addValue("cursorStart",Timestamp.from(before.startAt())).addValue("cursorId",before.id());}
        String pageSql="""
                select r.*,
                  max(t.occurred_at) filter (where t.to_status='CHECKED_IN') checked_in_at,
                  max(t.occurred_at) filter (where t.to_status='COMPLETED') completed_at
                from m1.reservations r
                left join m1.reservation_transitions t on t.reservation_id=r.id
                where %s%s
                group by r.id order by r.start_at desc,r.id desc limit :limit
                """.formatted(predicate,boundary);
        var rows=jdbc.query(pageSql,pageParameters,(row,index)->{
                    Instant checkedAt=instant(row.getTimestamp("checked_in_at")),completedAt=instant(row.getTimestamp("completed_at"));
                    long duration=checkedAt==null?0:Math.max(0,Duration.between(checkedAt,completedAt==null?serverTime:completedAt).getSeconds());
                    return new Entry(row.getObject("id",UUID.class),row.getObject("room_id",UUID.class),row.getObject("seat_id",UUID.class),
                            row.getTimestamp("start_at").toInstant(),row.getTimestamp("end_at").toInstant(),ReservationStatus.valueOf(row.getString("status")),
                            checkedAt,completedAt,duration,row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant());
                });
        boolean more=rows.size()>size;var items=List.copyOf(rows.subList(0,Math.min(size,rows.size())));
        Entry last=items.isEmpty()?null:items.getLast();Position next=more?new Position(last.startAt(),last.id()):null;
        return new Result(items,next,new Summary(total,Collections.unmodifiableMap(statuses),checked,violations,eligible,rate,
                reserved,actual,utilization,rooms,seats),serverTime);
    }

    private long number(String sql,SqlParameterSource parameters){return Objects.requireNonNull(jdbc.queryForObject(sql,parameters,Long.class));}
    private List<Count> counts(String column,String predicate,SqlParameterSource parameters){
        if(!Set.of("room_id","seat_id").contains(column))throw new IllegalArgumentException("count column");
        return jdbc.query("select r."+column+" id,count(*) total from m1.reservations r where "+predicate+" group by r."+column+" order by total desc,r."+column,
                parameters,(row,index)->new Count(row.getObject("id",UUID.class),row.getLong("total")));
    }
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
}
