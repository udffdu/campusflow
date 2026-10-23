package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleService {
    private final JdbcTemplate db; private final AuditService audit;
    RoleService(JdbcTemplate db,AuditService audit) { this.db=db; this.audit=audit; }
    public List<Role> list() { return db.query("select * from roles order by code",(rs,n)->new Role(rs.getString("code"),rs.getString("name"),db.queryForList("select permission from role_permissions where role_code=? order by permission",String.class,rs.getString("code")))); }
    public List<Permission> permissions() { return db.query("select * from permission_catalog order by code",(rs,n)->new Permission(rs.getString("code"),rs.getString("name"),rs.getBoolean("global_only"))); }
    @Transactional public Department department(UUID id,DepartmentWrite input,Identity actor,String requestId) {
        boolean create=id==null;if(create)id=UUID.randomUUID();
        if(create)db.update("insert into departments values(?,?,?)",id,input.code(),input.name());
        else if(db.update("update departments set name=? where id=? and code=?",input.name(),id,input.code())==0)throw ApiException.bad("DEPARTMENT","院系不存在，或尝试修改稳定编号");
        audit.local(actor.id(),id,create?"DEPARTMENT.CREATE":"DEPARTMENT.UPDATE","DEPARTMENT",id.toString(),"SUCCESS",null,requestId);
        return new Department(id,input.code(),input.name());
    }
    @Transactional public Role write(RoleWrite input,boolean create,Identity actor,String requestId) {
        if(Set.of("ADMIN","SPACE_MANAGER","AUDITOR","MEMBER").contains(input.code())) throw ApiException.bad("BUILTIN_ROLE","内置角色受保护，请新建自定义角色");
        var allowed=new HashSet<>(db.queryForList("select code from permission_catalog where not global_only",String.class));
        if(!allowed.containsAll(input.permissions())) throw ApiException.bad("PERMISSION","权限不存在，或仅允许授予内置系统管理员");
        if(create) db.update("insert into roles(code,name) values(?,?)",input.code(),input.name());
        else if(db.update("update roles set name=? where code=?",input.name(),input.code())==0) throw ApiException.missing();
        db.update("delete from role_permissions where role_code=?",input.code());
        input.permissions().forEach(permission -> db.update("insert into role_permissions values(?,?)",input.code(),permission));
        audit.local(actor.id(),actor.departmentId(),create?"ROLE.CREATE":"ROLE.UPDATE","ROLE",input.code(),"SUCCESS",null,requestId);
        return new Role(input.code(),input.name(),input.permissions().stream().sorted().toList());
    }
}
