package io.campusflow.seats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test database owner only: no Spring app or booking route assembly; absent from production jar. */
public final class DatabaseFixture {
    public static void main(String[] args)throws Exception {
        try(var pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start()){
            var json=new ObjectMapper();var input=new BufferedReader(new InputStreamReader(System.in));String line;
            System.out.println("FIXTURE_READY");System.out.flush();
            while((line=input.readLine())!=null&&!line.equals("STOP")){
                String op=json.readTree(line).path("op").asText();Object value;
                if(op.equals("connection"))value=Map.of("url",pg.getJdbcUrl("postgres","postgres"));
                else if(op.equals("stopDatabase")){pg.close();value=Map.of();}
                else if(op.equals("openCheckIn")){
                    var jdbc=new JdbcTemplate(pg.getPostgresDatabase());UUID id=UUID.fromString(json.readTree(line).path("reservationId").asText());
                    java.time.Instant start=java.time.Instant.now().minusSeconds(1);
                    if(jdbc.update("update m1.reservations set start_at=?,end_at=?,updated_at=? where id=?",
                            java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)),java.sql.Timestamp.from(start),id)!=1)
                        throw new IllegalArgumentException("reservation fixture not found");
                    value=Map.of("reservationId",id);
                }
                else if(op.equals("setPeriod")){
                    var command=json.readTree(line);var jdbc=new JdbcTemplate(pg.getPostgresDatabase());UUID id=UUID.fromString(command.path("reservationId").asText());
                    java.time.Instant start=java.time.Instant.parse(command.path("startAt").asText()),end=java.time.Instant.parse(command.path("endAt").asText());
                    if(!start.isBefore(end)||jdbc.update("update m1.reservations set start_at=?,end_at=?,updated_at=now() where id=?",
                            java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),id)!=1)throw new IllegalArgumentException("reservation fixture not found or period invalid");
                    value=Map.of("reservationId",id);
                }
                else if(op.equals("triggerReminderPair")){
                    var command=json.readTree(line);var jdbc=new JdbcTemplate(pg.getPostgresDatabase());UUID id=UUID.fromString(command.path("reservationId").asText());
                    if(jdbc.queryForObject("select count(*) from m1.reservations where id=? and status='CONFIRMED'",Integer.class,id)!=1)
                        throw new IllegalArgumentException("confirmed reservation fixture not found");
                    int inserted=0;
                    for(String type:List.of("RESERVATION_START_REMINDER","RESERVATION_LATE_REMINDER"))inserted+=jdbc.update("""
                            insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at,next_attempt_at)
                            select ?,r.id,?,jsonb_build_object('reservationId',r.id::text,'userId',r.user_id::text,
                              'roomId',r.room_id::text,'seatId',r.seat_id::text,'startAt',r.start_at::text,'endAt',r.end_at::text),now(),now()
                            from m1.reservations r where r.id=? and r.status='CONFIRMED'
                            on conflict(reservation_id,event_type) do nothing
                            """,UUID.randomUUID(),type,id);
                    int total=jdbc.queryForObject("select count(*) from m1.outbox_events where reservation_id=? and event_type in ('RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER')",Integer.class,id);
                    if(total!=2)throw new IllegalStateException("reminder fixture did not create both events");
                    value=Map.of("reservationId",id,"inserted",inserted);
                }
                else if(op.equals("makeNoShowDue")){
                    var jdbc=new JdbcTemplate(pg.getPostgresDatabase());
                    UUID id=UUID.fromString(json.readTree(line).path("reservationId").asText());
                    java.time.Instant start=java.time.Instant.now().minusSeconds(15*60+2);
                    java.time.Instant end=start.plusSeconds(60*60);
                    int changed=jdbc.update("""
                            update m1.reservations set start_at=?,end_at=?,updated_at=now()
                            where id=? and status='CONFIRMED'
                            """,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),id);
                    if(changed!=1)throw new IllegalArgumentException("confirmed reservation fixture not found");
                    value=Map.of("reservationId",id,"startAt",start.toString(),"endAt",end.toString());
                }
                else if(op.equals("seedOperations")){
                    var command=json.readTree(line);var jdbc=new JdbcTemplate(pg.getPostgresDatabase());
                    value=seedOperations(jdbc,UUID.fromString(command.path("roomId").asText()),
                            List.of(UUID.fromString(command.path("seatIds").get(0).asText()),UUID.fromString(command.path("seatIds").get(1).asText()),
                                    UUID.fromString(command.path("seatIds").get(2).asText()),UUID.fromString(command.path("seatIds").get(3).asText())),
                            UUID.fromString(command.path("outsideRoomId").asText()),UUID.fromString(command.path("outsideSeatId").asText()));
                }
                else if(op.equals("snapshot")){
                    var jdbc=new JdbcTemplate(pg.getPostgresDatabase());var snapshot=new TreeMap<String,Object>();
                    for(String table:List.of("reservations","reservation_requests","outbox_events","reservation_transitions","reservation_violations","checkin_rate_limits"))snapshot.put(table,jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from m1."+table+" t",String.class));
                    value=snapshot;
                }else throw new IllegalArgumentException("unknown database fixture operation");
                System.out.println("FIXTURE_REPLY "+json.writeValueAsString(value));System.out.flush();
            }
        }
    }

    private static Map<String,Object> seedOperations(JdbcTemplate jdbc,UUID room,List<UUID> seats,UUID outsideRoom,UUID outsideSeat){
        java.time.Instant anchor=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        java.time.Instant from=anchor.minus(java.time.Duration.ofDays(7)),to=anchor.plus(java.time.Duration.ofDays(1));
        operation(jdbc,room,seats.get(0),anchor.minus(java.time.Duration.ofDays(5)),java.time.Duration.ofHours(2),"COMPLETED",
                java.time.Duration.ofMinutes(5),java.time.Duration.ofMinutes(90));
        operation(jdbc,room,seats.get(1),anchor.minus(java.time.Duration.ofDays(4)),java.time.Duration.ofHours(1),"NO_SHOW",null,null);
        operation(jdbc,room,seats.get(2),anchor.minus(java.time.Duration.ofMinutes(30)),java.time.Duration.ofHours(1),"CHECKED_IN",
                java.time.Duration.ofMinutes(5),null);
        operation(jdbc,room,seats.get(3),anchor.plus(java.time.Duration.ofMinutes(30)),java.time.Duration.ofHours(1),"CONFIRMED",null,null);
        operation(jdbc,room,seats.get(0),anchor.minus(java.time.Duration.ofDays(2)),java.time.Duration.ofHours(1),"DISRUPTED",null,null);
        operation(jdbc,outsideRoom,outsideSeat,anchor.minus(java.time.Duration.ofDays(3)),java.time.Duration.ofHours(1),"COMPLETED",
                java.time.Duration.ZERO,java.time.Duration.ofMinutes(30));
        return Map.of("from",from.toString(),"to",to.toString(),"expectedScopedTotal",5,"expectedCheckedIn",2,"expectedViolations",1);
    }

    private static void operation(JdbcTemplate jdbc,UUID room,UUID seat,java.time.Instant start,java.time.Duration length,String status,
                                  java.time.Duration checkOffset,java.time.Duration completeOffset){
        UUID id=UUID.randomUUID();int version=completeOffset!=null?3:checkOffset!=null?2:1;
        jdbc.update("""
                insert into m1.reservations(id,user_id,room_id,seat_id,start_at,end_at,status,source,resource_version,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,'M1',1,?,?,?)
                """,id,UUID.randomUUID(),room,seat,java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plus(length)),status,version,
                java.sql.Timestamp.from(start.minusSeconds(3600)),java.sql.Timestamp.from(java.time.Instant.now()));
        if(checkOffset!=null)transition(jdbc,id,1,"CONFIRMED","CHECKED_IN",start.plus(checkOffset));
        if(completeOffset!=null)transition(jdbc,id,2,"CHECKED_IN","COMPLETED",start.plus(completeOffset));
    }

    private static void transition(JdbcTemplate jdbc,UUID reservation,int index,String from,String to,java.time.Instant at){
        jdbc.update("""
                insert into m1.reservation_transitions(transition_id,reservation_id,from_status,to_status,reason,reservation_version,occurred_at)
                values(?,?,?,?,'OPERATIONS_TEST_FIXTURE',?,?)
                """,UUID.randomUUID(),reservation,from,to,index+1,java.sql.Timestamp.from(at));
    }
}
