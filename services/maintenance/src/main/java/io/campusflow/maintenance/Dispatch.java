package io.campusflow.maintenance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;
@Service
public class Dispatch {
 public record Input(UUID assigneeId,Long expectedVersion) {}
 private final JdbcTemplate db;private final EligibilityGuard eligibility;private final Management management;private final ObjectMapper json;
 public Dispatch(JdbcTemplate db,EligibilityGuard eligibility,Management management,ObjectMapper json){this.db=db;this.eligibility=eligibility;this.management=management;this.json=json;}
 private String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception e){throw new IllegalStateException(e);}}
 @Transactional public String assign(DClient.Actor actor,UUID id,String key,Input input){
  if(input.assigneeId()==null||input.expectedVersion()==null||input.expectedVersion()<1||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new Failure(400,"INVALID_DISPATCH");
  var rows=db.queryForList("select room_id,department_id,status,version from work_orders where id=? and (? or department_id=?)",id,actor.admin(),actor.departmentId());
  if(rows.isEmpty())throw new Failure(404,"NOT_FOUND");var order=rows.getFirst();
  String canonical=encode(Map.of("id",id,"assigneeId",input.assigneeId(),"expectedVersion",input.expectedVersion()));
  int inserted=db.update("insert into dispatch_results values(?,?,?,null) on conflict do nothing",actor.id(),key,canonical);
  var result=db.queryForMap("select canonical_content,response_json from dispatch_results where actor_id=? and idempotency_key=?",actor.id(),key);
  if(!canonical.equals(result.get("canonical_content")))throw new Failure(409,"IDEMPOTENCY_CONFLICT");
  if(inserted==0){if(result.get("response_json")==null)throw new IllegalStateException("Incomplete dispatch");return (String)result.get("response_json");}
  if(!"PENDING_DISPATCH".equals(order.get("status"))||((Number)order.get("version")).longValue()!=input.expectedVersion())throw new Failure(409,"DISPATCH_CONFLICT");
  eligibility.requireEligible(new AssigneeEligibility.Request(actor.id(),input.assigneeId(),(UUID)order.get("room_id"),(UUID)order.get("department_id")));
  Instant now=Instant.now();Timestamp time=Timestamp.from(now);
  int changed=db.update("update work_orders set status='ASSIGNED',assignee_id=?,dispatcher_id=?,assigned_at=?,updated_at=?,version=version+1 where id=? and status='PENDING_DISPATCH' and version=?",input.assigneeId(),actor.id(),time,time,id,input.expectedVersion());
  if(changed!=1)throw new Failure(409,"DISPATCH_CONFLICT");
  db.update("insert into work_order_history(id,work_order_id,actor_id,status,occurred_at,action,from_status,assignee_id) values(?,?,?,'ASSIGNED',?,'ASSIGNED','PENDING_DISPATCH',?)",UUID.randomUUID(),id,actor.id(),time,input.assigneeId());
  UUID event=UUID.randomUUID();String payload=encode(Map.of("eventId",event,"actorId",actor.id(),"departmentId",order.get("department_id"),"action","MAINTENANCE.ASSIGNED","objectType","WORK_ORDER","objectId",id,"outcome","SUCCESS","occurredAt",now));
  db.update("insert into audit_outbox(event_id,work_order_id,payload,created_at) values(?,?,?,?)",event,id,payload,time);
  String response=encode(management.detail(actor,id));db.update("update dispatch_results set response_json=? where actor_id=? and idempotency_key=?",response,actor.id(),key);return response;
 }
}
