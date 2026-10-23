package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import java.util.*;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate db; private final AuditService audit;
    NotificationService(JdbcTemplate db,AuditService audit) { this.db=db; this.audit=audit; }
    private Delivery map(ResultSet rs,int n) throws SQLException {
        return new Delivery(rs.getObject("id",UUID.class),rs.getString("source"),rs.getString("event_id"),rs.getObject("recipient_id",UUID.class),rs.getString("title"),rs.getString("status"),rs.getInt("attempts"),rs.getTimestamp("next_attempt_at").toInstant(),rs.getString("last_error"),rs.getTimestamp("created_at").toInstant());
    }
    @Transactional public Delivery receive(String source,NotificationEvent input) {
        String hash=audit.payloadHash(input); UUID id=UUID.randomUUID();
        db.update("insert into notification_tasks(id,source,event_id,recipient_id,payload_hash,title,body,object_type,object_id) values(?,?,?,?,?,?,?,?,?) on conflict(source,event_id,recipient_id) do nothing",
            id,source,input.eventId(),input.recipientId(),hash,input.title(),input.body(),input.objectType(),input.objectId());
        var row=db.queryForMap("select id,payload_hash from notification_tasks where source=? and event_id=? and recipient_id=?",source,input.eventId(),input.recipientId());
        if(!hash.equals(row.get("payload_hash"))) throw ApiException.conflict("同一通知事件已用于不同内容"); return get((UUID)row.get("id"),source);
    }
    @Transactional public Delivery announce(NotificationEvent input,Identity actor,String requestId) {
        Delivery result=receive("D",input);
        UUID department=db.queryForObject("select department_id from app_users where id=?",UUID.class,input.recipientId());
        audit.local(actor.id(),department,"NOTIFICATION.CREATE","DELIVERY",result.id().toString(),"SUCCESS",null,requestId);
        return result;
    }
    public Delivery get(UUID id,String source) { return db.query("select * from notification_tasks where id=? and source=?",this::map,id,source).stream().findFirst().orElseThrow(ApiException::missing); }
    @Transactional public void deliver() {
        var tasks=db.queryForList("select t.*,u.active as recipient_active from notification_tasks t join app_users u on u.id=t.recipient_id where t.status in ('PENDING','RETRY') and t.next_attempt_at<=now() order by t.created_at limit 50 for update of t skip locked");
        for(var task:tasks) {
            UUID id=(UUID)task.get("id"); int attempts=((Number)task.get("attempts")).intValue()+1;
            if((boolean)task.get("recipient_active")) {
                db.update("insert into inbox(id,user_id) values(?,?) on conflict(id) do nothing",id,task.get("recipient_id"));
                db.update("update notification_tasks set status='DELIVERED',attempts=?,delivered_at=now(),last_error=null where id=?",attempts,id);
            } else {
                db.update("update notification_tasks set status=?,attempts=?,next_attempt_at=now()+(? * interval '1 second'),last_error='RECIPIENT_DISABLED' where id=?",attempts>=5?"DEAD":"RETRY",attempts,Math.min(300,5*(1<<attempts)),id);
            }
        }
    }
    public Page<Notice> inbox(UUID owner,int page,int size) {
        var rows=db.query("select i.*,t.source,t.title,t.body,t.object_type,t.object_id from inbox i join notification_tasks t on t.id=i.id where i.user_id=? order by i.received_at desc,i.id limit ? offset ?",(rs,n)->new Notice(
            rs.getObject("id",UUID.class),rs.getString("source"),rs.getString("title"),rs.getString("body"),rs.getString("object_type"),rs.getString("object_id"),rs.getTimestamp("received_at").toInstant(),rs.getTimestamp("read_at")==null?null:rs.getTimestamp("read_at").toInstant()),owner,size,page*size);
        return new Page<>(rows,page,size,db.queryForObject("select count(*) from inbox where user_id=?",Long.class,owner));
    }
    public long unread(UUID owner) { return db.queryForObject("select count(*) from inbox where user_id=? and read_at is null",Long.class,owner); }
    @Transactional public void read(UUID owner,UUID id) {
        if(db.update("update inbox set read_at=coalesce(read_at,now()) where id=? and user_id=?",id,owner)==0) throw ApiException.missing();
    }
    @Transactional public void readAll(UUID owner) { db.update("update inbox set read_at=now() where user_id=? and read_at is null",owner); }
    public Page<Delivery> tasks(String status,int page,int size) {
        String where=status==null||status.isBlank()?"":" where status=?"; var args=new ArrayList<Object>(); if(!where.isEmpty()) args.add(status);
        long total=db.queryForObject("select count(*) from notification_tasks"+where,Long.class,args.toArray()); args.add(size); args.add(page*size);
        return new Page<>(db.query("select * from notification_tasks"+where+" order by created_at desc,id limit ? offset ?",this::map,args.toArray()),page,size,total);
    }
    @Transactional public void retry(UUID id,Identity user,String requestId) {
        if(db.update("update notification_tasks set status='PENDING',attempts=0,next_attempt_at=now(),last_error=null where id=? and status in ('DEAD','RETRY')",id)==0) throw ApiException.conflict("只能重试失败或等待重试的投递任务");
        audit.local(user.id(),user.departmentId(),"NOTIFICATION.RETRY","DELIVERY",id.toString(),"SUCCESS",null,requestId);
    }
}
