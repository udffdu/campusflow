package io.campusflow.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class Management {
    private final JdbcTemplate db;
    public Management(JdbcTemplate db) { this.db=db; }
    private String scope(DClient.Actor actor,List<Object> args) {
        if(actor.admin()) return "true"; // ADMIN still requires D allowed=true for maintenance.read.scope.
        args.add(actor.departmentId()); return "department_id=?";
    }
    private Map<String,Object> row(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",rs.getObject("id"));result.put("roomId",rs.getObject("room_id"));
        result.put("faultType",rs.getString("fault_type"));result.put("description",rs.getString("description"));
        result.put("status",rs.getString("status"));result.put("createdAt",rs.getTimestamp("created_at").toInstant());
        // V1 contains only creation facts; V2 will provide persisted assignment fields.
        result.put("assigneeId",null);result.put("dispatcherId",null);result.put("assignedAt",null);
        result.put("version",1L);result.put("updatedAt",rs.getTimestamp("created_at").toInstant());
        return result;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> list(DClient.Actor actor,int page,int size,String status) {
        if(page<0||page>100000||size<1||size>100) throw new Failure(400,"INVALID_PAGE");
        if(status!=null&&!Set.of("PENDING_DISPATCH").contains(status)) throw new Failure(400,"INVALID_STATUS");
        List<Object> args=new ArrayList<>();String where=scope(actor,args);
        if(status!=null){where+=" and status=?";args.add(status);}
        long total=db.queryForObject("select count(*) from work_orders where "+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var items=db.query("select id,room_id,fault_type,left(description,120) as description,status,created_at from work_orders where "+where+" order by created_at desc,id desc limit ? offset ?",this::row,args.toArray());
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(DClient.Actor actor,UUID id) {
        List<Object> args=new ArrayList<>();String where=scope(actor,args);args.add(id);
        var rows=db.query("select id,room_id,fault_type,description,status,created_at from work_orders where "+where+" and id=?",this::row,args.toArray());
        if(rows.isEmpty()) throw new Failure(404,"NOT_FOUND");
        return rows.getFirst(); // Permission-based projection: no reporter, department or history identities.
    }
}
