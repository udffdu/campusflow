package io.campusflow.maintenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class Orders {
    public record Input(UUID roomId,String faultType,String description) {}
    private final JdbcTemplate db; private final ObjectMapper json; private final WorkOrderHistory history;
    public Orders(JdbcTemplate db,ObjectMapper json,WorkOrderHistory history) { this.db=db;this.json=json;this.history=history; }
    public Input validate(Input input) {
        if(input.roomId()==null || input.faultType()==null || !Set.of("LIGHTING","POWER","FURNITURE","HVAC","OTHER").contains(input.faultType()) || input.description()==null) throw new Failure(400,"INVALID_INPUT");
        String description=input.description().strip();int length=description.codePointCount(0,description.length());
        if(length<5 || length>1000) throw new Failure(400,"INVALID_DESCRIPTION");
        return new Input(input.roomId(),input.faultType(),description);
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    @Transactional
    public String create(DClient.Actor actor,DClient.Room room,String key,Input input) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new Failure(400,"INVALID_IDEMPOTENCY_KEY");
        String canonical=encode(input);
        // INSERT waits for an uncommitted competing key; rollback lets the waiter claim it.
        int inserted=db.update("insert into creation_results(reporter_id,idempotency_key,canonical_content) values(?,?,?) on conflict do nothing",actor.id(),key,canonical);
        var existing=db.queryForMap("select canonical_content,response_json from creation_results where reporter_id=? and idempotency_key=?",actor.id(),key);
        if(!canonical.equals(existing.get("canonical_content"))) throw new Failure(409,"IDEMPOTENCY_CONFLICT");
        if(inserted==0) {
            if(existing.get("response_json")==null) throw new IllegalStateException("Incomplete committed result");
            return (String)existing.get("response_json");
        }
        UUID id=UUID.randomUUID();Instant now=Instant.now();Timestamp timestamp=Timestamp.from(now);
        db.update("insert into work_orders(id,reporter_id,room_id,department_id,resource_version,fault_type,description,status,created_at) values(?,?,?,?,?,?,?,?,?)",id,actor.id(),room.id(),room.departmentId(),room.version(),input.faultType(),input.description(),"PENDING_DISPATCH",timestamp);
        db.update("insert into work_order_history(id,work_order_id,actor_id,status,occurred_at) values(?,?,?,?,?)",UUID.randomUUID(),id,actor.id(),"PENDING_DISPATCH",timestamp);
        UUID eventId=UUID.randomUUID();
        String event=encode(Map.of("eventId",eventId,"actorId",actor.id(),"departmentId",room.departmentId(),"action","MAINTENANCE.REPORTED","objectType","WORK_ORDER","objectId",id,"outcome","SUCCESS","occurredAt",now));
        db.update("insert into audit_outbox(event_id,work_order_id,payload,created_at) values(?,?,?,?)",eventId,id,event,timestamp);
        String response=encode(detail(actor.id(),id));
        db.update("update creation_results set work_order_id=?,response_json=? where reporter_id=? and idempotency_key=?",id,response,actor.id(),key);
        return response;
    }
    private Map<String,Object> map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        var result=new LinkedHashMap<String,Object>();
        // V1 only stores creation facts. These values follow Format v1 without rewriting V1.
        // Stage 3 will read persisted assignment/version columns introduced by V2.
        result.put("assigneeId",null);
        result.put("dispatcherId",null);
        result.put("assignedAt",null);
        result.put("updatedAt",rs.getTimestamp("created_at").toInstant());
        result.put("version",1L);
        result.put("id",rs.getObject("id"));result.put("reporterId",rs.getObject("reporter_id"));result.put("roomId",rs.getObject("room_id"));result.put("departmentId",rs.getObject("department_id"));result.put("faultType",rs.getString("fault_type"));result.put("description",rs.getString("description"));result.put("status",rs.getString("status"));result.put("createdAt",rs.getTimestamp("created_at").toInstant());return result;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> list(UUID reporter,int page,int size) {
        if(page<0 || page>100000 || size<1 || size>100) throw new Failure(400,"INVALID_PAGE");
        var items=db.query("select * from work_orders where reporter_id=? order by created_at desc,id desc limit ? offset ?",this::map,reporter,size,(long)page*size);
        long total=db.queryForObject("select count(*) from work_orders where reporter_id=?",Long.class,reporter);
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(UUID reporter,UUID id) {
        var rows=db.query("select * from work_orders where reporter_id=? and id=?",this::map,reporter,id);
        if(rows.isEmpty()) throw new Failure(404,"NOT_FOUND");
        var result=rows.getFirst();
        result.put("history",history.forOrder(id));
        return result;
    }
}
