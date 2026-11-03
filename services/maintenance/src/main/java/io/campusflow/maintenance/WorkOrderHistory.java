package io.campusflow.maintenance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
/** Local business history; not a D audit delivery receipt. Call only after order authorization. */
@Component
public class WorkOrderHistory {
 public record Entry(UUID id,String action,UUID actorId,String fromStatus,String toStatus,UUID assigneeId,String remark,Instant occurredAt) {}
 private final JdbcTemplate db;
 public WorkOrderHistory(JdbcTemplate db){this.db=db;}
 List<Entry> forOrder(UUID id){return db.query("select id,action,actor_id,from_status,status,assignee_id,remark,occurred_at from work_order_history where work_order_id=? order by occurred_at,id",(rs,n)->new Entry(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getObject(6,UUID.class),rs.getString(7),rs.getTimestamp(8).toInstant()),id);}
}
