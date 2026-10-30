package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailNotificationService {
    private static final int MAX_ATTEMPTS=5;
    private final JdbcTemplate db;
    private final AuditService audit;
    private final Clock clock;
    EmailNotificationService(JdbcTemplate db,AuditService audit,Clock clock){this.db=db;this.audit=audit;this.clock=clock;}

    private Delivery map(ResultSet row,int index)throws SQLException {
        return new Delivery(row.getObject("id",UUID.class),row.getString("source"),row.getString("event_id"),
                row.getObject("recipient_id",UUID.class),row.getString("title"),row.getString("status"),
                row.getInt("attempts"),row.getTimestamp("next_attempt_at").toInstant(),row.getString("last_error"),
                row.getTimestamp("created_at").toInstant());
    }
    @Transactional public Delivery receive(String source,NotificationEvent input) {
        String hash=audit.payloadHash(input);UUID id=UUID.randomUUID();
        db.update("""
                insert into email_notification_tasks
                (id,source,event_id,recipient_id,payload_hash,title,body,object_type,object_id)
                values (?,?,?,?,?,?,?,?,?) on conflict(source,event_id,recipient_id) do nothing
                """,id,source,input.eventId(),input.recipientId(),hash,input.title(),input.body(),input.objectType(),input.objectId());
        var row=db.queryForMap("select id,payload_hash from email_notification_tasks where source=? and event_id=? and recipient_id=?",
                source,input.eventId(),input.recipientId());
        if(!hash.equals(row.get("payload_hash")))throw ApiException.conflict("同一邮件事件已用于不同内容");
        return get((UUID)row.get("id"),source);
    }
    public Delivery get(UUID id,String source){return db.query("select * from email_notification_tasks where id=? and source=?",this::map,id,source).stream().findFirst().orElseThrow(ApiException::missing);}

    record Claim(UUID id,String email,String title,String body,boolean recipientActive,int attempts){}
    @Transactional List<Claim> claim(int limit,Duration lease) {
        Instant now=clock.instant();
        var rows=db.query("""
                select t.id,u.email,t.title,t.body,u.active,t.attempts
                from email_notification_tasks t join app_users u on u.id=t.recipient_id
                where ((t.status in ('PENDING','RETRY') and t.next_attempt_at<=?)
                       or (t.status='DELIVERING' and t.lease_until<=?))
                order by t.next_attempt_at,t.created_at,t.id
                limit ? for update of t skip locked
                """,(row,index)->new Claim(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4),row.getBoolean(5),row.getInt(6)+1),
                Timestamp.from(now),Timestamp.from(now),limit);
        for(var row:rows)db.update("update email_notification_tasks set status='DELIVERING',attempts=?,lease_until=?,last_error=null where id=?",
                row.attempts(),Timestamp.from(now.plus(lease)),row.id());
        return List.copyOf(rows);
    }
    @Transactional void delivered(UUID id) {
        db.update("update email_notification_tasks set status='DELIVERED',lease_until=null,last_error=null,delivered_at=? where id=? and status='DELIVERING'",
                Timestamp.from(clock.instant()),id);
    }
    @Transactional void failed(Claim claim,String reason) {
        String safe=Set.of("RECIPIENT_DISABLED","RECIPIENT_EMAIL_MISSING","EMAIL_CHANNEL_DISABLED","EMAIL_DELIVERY_FAILED").contains(reason)?reason:"EMAIL_DELIVERY_FAILED";
        boolean dead=claim.attempts()>=MAX_ATTEMPTS;
        long seconds=Math.min(300,5L<<(Math.min(claim.attempts(),6)-1));
        db.update("update email_notification_tasks set status=?,lease_until=null,last_error=?,next_attempt_at=? where id=? and status='DELIVERING'",
                dead?"DEAD":"RETRY",safe,Timestamp.from(clock.instant().plusSeconds(seconds)),claim.id());
    }
    @Transactional public void retry(UUID id) {
        if(db.update("update email_notification_tasks set status='PENDING',attempts=0,next_attempt_at=?,lease_until=null,last_error=null where id=? and status in ('DEAD','RETRY')",
                Timestamp.from(clock.instant()),id)==0)throw ApiException.conflict("只能重试失败或等待重试的邮件任务");
    }
}
