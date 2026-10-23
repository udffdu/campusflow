package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final JdbcTemplate db; private final ObjectMapper mapper;
    AuditService(JdbcTemplate db,ObjectMapper mapper) { this.db=db; this.mapper=mapper; }
    String payloadHash(Object input) {
        try { return IdentityService.hash(mapper.writeValueAsString(input)); }
        catch(Exception ex) { throw new IllegalStateException("Cannot serialize typed event",ex); }
    }
    void local(UUID actor,UUID department,String action,String type,String object,String outcome,String reason,String requestId) {
        var input=new AuditEvent(UUID.randomUUID().toString(),actor,department,action,type,object,outcome,reason,Instant.now());
        receive("D",input,requestId);
    }
    @Transactional public UUID receive(String source,AuditEvent input,String requestId) {
        if(input.occurredAt().isAfter(Instant.now().plusSeconds(60))) throw ApiException.bad("EVENT_TIME","事件时间不能晚于当前时间一分钟");
        String hash=payloadHash(input); UUID id=UUID.randomUUID();
        db.update("insert into audit_events(id,source,event_id,actor_id,department_id,action,object_type,object_id,outcome,reason_code,request_id,payload_hash,occurred_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(source,event_id) do nothing",
            id,source,input.eventId(),input.actorId(),input.departmentId(),input.action(),input.objectType(),input.objectId(),input.outcome(),input.reasonCode(),requestId,hash,Timestamp.from(input.occurredAt()));
        var row=db.queryForMap("select id,payload_hash from audit_events where source=? and event_id=?",source,input.eventId());
        if(!hash.equals(row.get("payload_hash"))) throw ApiException.conflict("同一审计事件编号已用于不同内容"); return (UUID)row.get("id");
    }
    public Page<Audit> list(Identity user,String source,String outcome,int page,int size) {
        var args=new ArrayList<Object>(); String where=" where 1=1";
        if(!user.admin()) { where+=" and a.department_id=?"; args.add(user.departmentId()); }
        if(source!=null&&!source.isBlank()) { where+=" and a.source=?"; args.add(source); }
        if(outcome!=null&&!outcome.isBlank()) { where+=" and a.outcome=?"; args.add(outcome); }
        long total=db.queryForObject("select count(*) from audit_events a"+where,Long.class,args.toArray());
        args.add(size); args.add(page*size);
        var rows=db.query("select a.*,u.display_name as actor_name from audit_events a left join app_users u on u.id=a.actor_id"+where+" order by a.occurred_at desc,a.id limit ? offset ?",(rs,n)->new Audit(
            rs.getObject("id",UUID.class),rs.getString("source"),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),rs.getObject("department_id",UUID.class),rs.getString("action"),rs.getString("object_type"),rs.getString("object_id"),rs.getString("outcome"),rs.getString("reason_code"),rs.getString("request_id"),rs.getTimestamp("occurred_at").toInstant()),args.toArray());
        return new Page<>(rows,page,size,total);
    }
}
