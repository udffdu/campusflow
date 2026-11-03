package io.campusflow.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** V1 creation facts, projected to the final format; read only after order authorization. */
@Component
public class WorkOrderHistory {
    public record Entry(UUID id, String action, UUID actorId, String fromStatus,
                        String toStatus, UUID assigneeId, String remark, Instant occurredAt) {}
    private final JdbcTemplate db;
    public WorkOrderHistory(JdbcTemplate db) { this.db = db; }
    List<Entry> forOrder(UUID id) {
        // V1 has no other legal transition. Row ID, actor, status and time are real facts.
        return db.query("""
                select id, actor_id, status, occurred_at
                from work_order_history where work_order_id=? order by occurred_at,id
                """, (rs, n) -> new Entry(rs.getObject("id", UUID.class), "CREATED",
                rs.getObject("actor_id", UUID.class), null, rs.getString("status"),
                null, null, rs.getTimestamp("occurred_at").toInstant()), id);
    }
}
