package io.campusflow.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** M3-only lifecycle. Queued M1 intentions are explicitly not delivery receipts. */
@Service
public class Lifecycle {
    public enum Action {
        START("ASSIGNED","IN_PROGRESS","REPAIR_STARTED",false,false),
        COMPLETE("IN_PROGRESS","PENDING_ACCEPTANCE","COMPLETION_SUBMITTED",false,true),
        PASS("PENDING_ACCEPTANCE","COMPLETED","ACCEPTANCE_PASSED",true,false),
        REJECT("PENDING_ACCEPTANCE","IN_PROGRESS","ACCEPTANCE_REJECTED",true,true);
        final String from,to,history; final boolean acceptance,requiredRemark;
        Action(String from,String to,String history,boolean acceptance,boolean requiredRemark) {
            this.from=from;this.to=to;this.history=history;this.acceptance=acceptance;this.requiredRemark=requiredRemark;
        }
    }
    public record Input(Long expectedVersion,String remark) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final WorkOrderHistory history;
    public Lifecycle(JdbcTemplate db,ObjectMapper json,WorkOrderHistory history) {this.db=db;this.json=json;this.history=history;}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> row(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        var result=new LinkedHashMap<String,Object>();
        for(String name:List.of("id","reporter_id","room_id","department_id","assignee_id","dispatcher_id")) {
            String key=switch(name){case "reporter_id"->"reporterId";case "room_id"->"roomId";case "department_id"->"departmentId";case "assignee_id"->"assigneeId";case "dispatcher_id"->"dispatcherId";default->name;};
            result.put(key,rs.getObject(name));
        }
        result.put("faultType",rs.getString("fault_type"));result.put("description",rs.getString("description"));result.put("status",rs.getString("status"));result.put("version",rs.getLong("version"));
        for(String name:List.of("created_at","assigned_at","updated_at")) {
            String key=switch(name){case "created_at"->"createdAt";case "assigned_at"->"assignedAt";default->"updatedAt";};
            var time=rs.getTimestamp(name);result.put(key,time==null?null:time.toInstant());
        }
        // This first iteration has no M1 sender. Do not imply BLOCKED or RELEASED was acknowledged.
        result.put("maintenanceSync", "ASSIGNED".equals(result.get("status"))?"NOT_REQUESTED":"UNCONFIRMED");
        return result;
    }
    private Map<String,Object> find(UUID actor,UUID id,boolean acceptance) {
        var rows=db.query("select * from work_orders where id=? and "+(acceptance?"reporter_id":"assignee_id")+"=?",this::row,id,actor);
        if(rows.isEmpty())throw new Failure(404,"NOT_FOUND");
        return rows.getFirst();
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(UUID actor,UUID id) {var result=find(actor,id,false);result.put("history",history.forOrder(id));return result;}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> list(UUID actor,int page,int size) {
        if(page<0||page>100000||size<1||size>100)throw new Failure(400,"INVALID_PAGE");
        var items=db.query("select * from work_orders where assignee_id=? order by created_at desc,id desc limit ? offset ?",this::row,actor,size,(long)page*size);
        long total=db.queryForObject("select count(*) from work_orders where assignee_id=?",Long.class,actor);
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    @Transactional
    public String execute(UUID actor,UUID id,Action action,String key,Input input) {
        if(input==null||input.expectedVersion()==null||input.expectedVersion()<1||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new Failure(400,"INVALID_LIFECYCLE_INPUT");
        String remark=input.remark()==null?"":input.remark().strip();
        int length=remark.codePointCount(0,remark.length());
        if(length>1000||(action.requiredRemark&&length==0))throw new Failure(400,"INVALID_REMARK");
        var order=find(actor,id,action.acceptance);
        if(action.acceptance&&actor.equals(order.get("assigneeId")))throw new Failure(403,"SELF_ACCEPTANCE_FORBIDDEN");
        String canonical=encode(new Input(input.expectedVersion(),remark));
        int inserted=db.update("insert into lifecycle_results(actor_id,action,work_order_id,idempotency_key,canonical_content) values(?,?,?,?,?) on conflict do nothing",actor,action.name(),id,key,canonical);
        var saved=db.queryForMap("select canonical_content,response_json from lifecycle_results where actor_id=? and action=? and work_order_id=? and idempotency_key=?",actor,action.name(),id,key);
        if(!canonical.equals(saved.get("canonical_content")))throw new Failure(409,"IDEMPOTENCY_CONFLICT");
        if(inserted==0){if(saved.get("response_json")==null)throw new IllegalStateException("Incomplete lifecycle result");return (String)saved.get("response_json");}
        Instant now=Instant.now();var time=Timestamp.from(now);
        String owner=action.acceptance?"reporter_id":"assignee_id";
        int changed=db.update("update work_orders set status=?,updated_at=?,version=version+1 where id=? and status=? and version=? and "+owner+"=?",action.to,time,id,action.from,input.expectedVersion(),actor);
        if(changed!=1)throw new Failure(409,"LIFECYCLE_CONFLICT");
        db.update("insert into work_order_history(id,work_order_id,actor_id,status,occurred_at,action,from_status,assignee_id,remark) values(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,actor,action.to,time,action.history,action.from,order.get("assigneeId"),remark.isEmpty()?null:remark);
        UUID event=UUID.randomUUID();String payload=encode(Map.of("eventId",event,"actorId",actor,"departmentId",order.get("departmentId"),"action","MAINTENANCE."+action.history,"objectType","WORK_ORDER","objectId",id,"outcome","SUCCESS","occurredAt",now));
        db.update("insert into audit_outbox(event_id,work_order_id,payload,created_at) values(?,?,?,?)",event,id,payload,time);
        if(action==Action.START||action==Action.PASS)db.update("insert into maintenance_intents(work_order_id,operation_version,room_id,target,created_at) values(?,?,?,?,?)",id,input.expectedVersion()+1,order.get("roomId"),action==Action.START?"BLOCKED":"RELEASED",time);
        var response=find(actor,id,action.acceptance);response.put("history",history.forOrder(id));String encoded=encode(response);
        db.update("update lifecycle_results set response_json=? where actor_id=? and action=? and work_order_id=? and idempotency_key=?",encoded,actor,action.name(),id,key);
        return encoded;
    }
}
