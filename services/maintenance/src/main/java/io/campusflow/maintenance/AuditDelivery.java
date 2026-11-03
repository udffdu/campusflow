package io.campusflow.maintenance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
@Service
public class AuditDelivery {
 private final JdbcTemplate db;private final AuditTransport transport;
 public AuditDelivery(JdbcTemplate db,AuditTransport transport){this.db=db;this.transport=transport;}
 @Transactional public void deliver(){
  var rows=db.queryForList("select event_id,payload,attempts from audit_outbox where status in ('PENDING','RETRY') and next_attempt_at<=now() order by created_at,event_id limit 10 for update skip locked");
  for(var row:rows){int attempts=((Number)row.get("attempts")).intValue()+1;
   try{transport.send((String)row.get("payload"));db.update("update audit_outbox set status='DELIVERED',delivered_at=now(),attempts=?,last_error_code=null where event_id=?",attempts,row.get("event_id"));}
   catch(Failure e){long seconds=Math.min(3600,5L*(1L<<Math.min(attempts,10)));db.update("update audit_outbox set status='RETRY',attempts=?,last_error_code=?,next_attempt_at=now()+(? * interval '1 second') where event_id=?",attempts,e.code,seconds,row.get("event_id"));}
  }
 }
}
