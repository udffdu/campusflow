package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private final JdbcTemplate db; private final ObjectMapper mapper; private final Access access; private final AuditService audit;
    ResourceService(JdbcTemplate db,ObjectMapper mapper,Access access,AuditService audit) { this.db=db; this.mapper=mapper; this.access=access; this.audit=audit; }
    // Recursion also catches disabled ancestors; M1 still owns occupancy and repairs.
    private static final String SELECT="""
        select r.*,not exists(with recursive ancestors as (
          select x.id,x.parent_id,x.active from resources x where x.id=r.id
          union all select p.id,p.parent_id,p.active from resources p join ancestors a on a.parent_id=p.id
        ) select 1 from ancestors where not active) as effective_active from resources r
        """;
    private Resource map(ResultSet rs,int ignored) throws SQLException {
        try { return new Resource(rs.getObject("id",UUID.class),rs.getString("type"),rs.getObject("parent_id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getObject("department_id",UUID.class),rs.getInt("capacity"),
            mapper.readValue(rs.getString("features"),new TypeReference<List<String>>(){}),mapper.readValue(rs.getString("opening_hours"),new TypeReference<List<OpeningHour>>(){}),
            rs.getBoolean("active"),rs.getBoolean("effective_active"),rs.getLong("version"),rs.getTimestamp("updated_at").toInstant()); }
        catch(java.io.IOException ex) { throw new SQLException("Invalid stored resource configuration",ex); }
    }
    public Resource get(UUID id) { return db.query(SELECT+" where r.id=?",this::map,id).stream().findFirst().orElseThrow(ApiException::missing); }
    public Page<Resource> list(String type,UUID parent,String query,int page,int size) {
        String where=" where 1=1"; var args=new ArrayList<Object>();
        if(type!=null&&!type.isBlank()) { where+=" and r.type=?"; args.add(type); }
        if(parent!=null) { where+=" and r.parent_id=?"; args.add(parent); }
        if(query!=null&&!query.isBlank()) { where+=" and (r.name ilike ? or r.code ilike ?)"; args.add("%"+query+"%"); args.add("%"+query+"%"); }
        long total=db.queryForObject("select count(*) from resources r"+where,Long.class,args.toArray());
        args.add(size); args.add(page*size);
        return new Page<>(db.query(SELECT+where+" order by r.type,r.code,r.id limit ? offset ?",this::map,args.toArray()),page,size,total);
    }
    private void validate(String type,int capacity,List<OpeningHour> hours) {
        if(type.equals("SEAT")&&capacity!=1) throw ApiException.bad("CAPACITY","座位容量必须为 1");
        if(type.equals("ROOM")&&capacity<1) throw ApiException.bad("CAPACITY","教室容量至少为 1");
        if(!type.equals("ROOM")&&!hours.isEmpty()) throw ApiException.bad("OPENING_HOURS","每周开放时段只配置在教室上");
        var sorted=hours.stream().sorted(Comparator.comparingInt(OpeningHour::day).thenComparing(OpeningHour::start)).toList();
        OpeningHour previous=null;
        for(var hour:sorted) {
            if(!hour.start().isBefore(hour.end())||hour.start().getSecond()!=0||hour.end().getSecond()!=0||hour.start().getNano()!=0||hour.end().getNano()!=0)
                throw ApiException.bad("OPENING_HOURS","开放时间按分钟设置，结束须晚于开始；跨夜请拆分");
            if(previous!=null&&previous.day()==hour.day()&&hour.start().isBefore(previous.end())) throw ApiException.bad("OPENING_HOURS","同一天的开放时段不能重叠");
            previous=hour;
        }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    @Transactional public Resource create(ResourceCreate input,Identity user,String requestId) {
        validate(input.type(),input.capacity(),input.openingHours());
        String parentType=switch(input.type()) { case "CAMPUS"->null; case "BUILDING"->"CAMPUS"; case "ROOM"->"BUILDING"; default->"ROOM"; };
        if(parentType==null) { if(input.parentId()!=null) throw ApiException.bad("PARENT","校区不能有父节点"); }
        else {
            if(input.parentId()==null) throw ApiException.bad("PARENT","请选择上级空间");
            db.queryForList("select id from resources where id=? for update",input.parentId());
            Resource parent=get(input.parentId());
            if(!parentType.equals(parent.type())||!parent.effectiveActive()) throw ApiException.bad("PARENT","上级空间类型不正确或已停用");
            if(input.type().equals("SEAT")&&!Objects.equals(parent.departmentId(),input.departmentId())) throw ApiException.bad("DEPARTMENT","座位须属于教室所在院系");
        }
        if(List.of("CAMPUS","BUILDING").contains(input.type())&&input.departmentId()!=null) throw ApiException.bad("DEPARTMENT","校区和楼栋不归属单个院系");
        if(List.of("ROOM","SEAT").contains(input.type())&&input.departmentId()==null) throw ApiException.bad("DEPARTMENT","教室和座位须指定院系");
        access.scope(user,input.departmentId()); UUID id=UUID.randomUUID();
        db.update("insert into resources(id,type,parent_id,code,name,department_id,capacity,features,opening_hours,active) values(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
            id,input.type(),input.parentId(),input.code(),input.name(),input.departmentId(),input.capacity(),json(input.features()),json(input.openingHours()),input.active());
        audit.local(user.id(),input.departmentId(),"SPACE.CREATE",input.type(),id.toString(),"SUCCESS",null,requestId); return get(id);
    }
    @Transactional public Resource update(UUID id,ResourceUpdate input,Identity user,String requestId) {
        Resource before=get(id); access.scope(user,before.departmentId()); validate(before.type(),input.capacity(),input.openingHours());
        int changed=db.update("update resources set name=?,capacity=?,features=?::jsonb,opening_hours=?::jsonb,active=?,version=nextval('resource_version'),updated_at=now() where id=? and version=?",
            input.name(),input.capacity(),json(input.features()),json(input.openingHours()),input.active(),id,input.expectedVersion());
        if(changed==0) throw ApiException.conflict("资源已被其他人修改，请刷新后再编辑");
        audit.local(user.id(),before.departmentId(),"SPACE.UPDATE",before.type(),id.toString(),"SUCCESS",null,requestId); return get(id);
    }
}
