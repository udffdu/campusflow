package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final int sessionHours; private final AuditService audit;
    private final String dummyHash;
    IdentityService(JdbcTemplate db,PasswordEncoder encoder,AuditService audit,@Value("${platform.session-hours}") int hours) {
        this.db=db; this.encoder=encoder; this.audit=audit; sessionHours=hours; dummyHash=encoder.encode(UUID.randomUUID().toString());
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    Identity identity(ResultSet row) throws SQLException {
        UUID id=row.getObject("id",UUID.class);
        var roles=db.queryForList("select role_code from user_roles where user_id=? order by role_code",String.class,id);
        var permissions=new TreeSet<>(db.queryForList("select distinct permission from role_permissions p join user_roles r on r.role_code=p.role_code where r.user_id=?",String.class,id));
        return new Identity(id,row.getString("username"),row.getString("display_name"),row.getObject("department_id",UUID.class),row.getBoolean("active"),roles,permissions);
    }
    public Identity get(UUID id) { return db.query("select * from app_users where id=?",(rs,n) -> identity(rs),id).stream().findFirst().orElseThrow(ApiException::missing); }
    public Optional<Identity> findSession(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return db.query("select u.* from app_users u join user_sessions s on s.user_id=u.id where s.token_hash=? and s.expires_at>now() and u.active",(rs,n)->identity(rs),hash(token)).stream().findFirst();
    }
    // Failed attempts must commit as well; the controller maps this explicit result to 401.
    @Transactional
    public Optional<String> login(Login login,String requestId) {
        if(login.password().getBytes(StandardCharsets.UTF_8).length>72) return Optional.empty();
        var rows=db.queryForList("select * from app_users where username=? for update",login.username());
        if(rows.isEmpty()) { encoder.matches(login.password(),dummyHash); return Optional.empty(); }
        var row=rows.getFirst(); UUID id=(UUID)row.get("id");
        var locked=(java.sql.Timestamp)row.get("locked_until");
        boolean valid=encoder.matches(login.password(),(String)row.get("password_hash"));
        if(!valid || !(boolean)row.get("active") || (locked!=null && locked.toInstant().isAfter(Instant.now()))) {
            int attempts=((Number)row.get("failed_attempts")).intValue()+1;
            db.update("update app_users set failed_attempts=?, locked_until=case when ?>=5 then now()+interval '5 minutes' else locked_until end where id=?",attempts,attempts,id);
            audit.local(id,(UUID)row.get("department_id"),"AUTH.LOGIN","USER",id.toString(),"FAILURE","INVALID_CREDENTIALS",requestId);
            return Optional.empty();
        }
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("update app_users set failed_attempts=0,locked_until=null where id=?",id);
        db.update("delete from user_sessions where expires_at<=now()");
        db.update("insert into user_sessions(token_hash,user_id,expires_at) values(?,?,?)",hash(token),id,java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(sessionHours))));
        audit.local(id,(UUID)row.get("department_id"),"AUTH.LOGIN","USER",id.toString(),"SUCCESS",null,requestId);
        return Optional.of(token);
    }
    @Transactional public void logout(String token,Identity user,String requestId) {
        if(token!=null) db.update("delete from user_sessions where token_hash=?",hash(token));
        audit.local(user.id(),user.departmentId(),"AUTH.LOGOUT","USER",user.id().toString(),"SUCCESS",null,requestId);
    }
    @Transactional public void password(Identity user,PasswordChange input,String requestId) {
        if(input.currentPassword().getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.bad("PASSWORD_MISMATCH","当前密码不正确");
        String stored=db.queryForObject("select password_hash from app_users where id=? for update",String.class,user.id());
        if(!encoder.matches(input.currentPassword(),stored)) throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_MISMATCH","当前密码不正确");
        checkPassword(input.newPassword());
        db.update("update app_users set password_hash=? where id=?",encoder.encode(input.newPassword()),user.id());
        db.update("delete from user_sessions where user_id=?",user.id());
        audit.local(user.id(),user.departmentId(),"AUTH.PASSWORD_CHANGE","USER",user.id().toString(),"SUCCESS",null,requestId);
    }
    static void checkPassword(String password) {
        if(password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.bad("PASSWORD_POLICY","密码须至少 12 个字符，且 UTF-8 编码不超过 72 字节");
    }
    public Page<Identity> list(int page,int size) {
        return new Page<>(db.query("select * from app_users order by created_at,id limit ? offset ?",(rs,n)->identity(rs),size,page*size),page,size,db.queryForObject("select count(*) from app_users",Long.class));
    }
    private void validate(UUID department,Set<String> roles) {
        if(db.queryForObject("select count(*) from departments where id=?",Integer.class,department)==0) throw ApiException.bad("DEPARTMENT","院系不存在");
        if(!new HashSet<>(db.queryForList("select code from roles",String.class)).containsAll(roles)) throw ApiException.bad("ROLE","角色不存在");
    }
    @Transactional public Identity create(UserCreate input,Identity actor,String requestId) {
        validate(input.departmentId(),input.roles()); checkPassword(input.password()); UUID id=UUID.randomUUID();
        db.update("insert into app_users(id,username,display_name,password_hash,department_id) values(?,?,?,?,?)",id,input.username(),input.displayName(),encoder.encode(input.password()),input.departmentId());
        input.roles().forEach(role -> db.update("insert into user_roles values(?,?)",id,role));
        audit.local(actor.id(),input.departmentId(),"USER.CREATE","USER",id.toString(),"SUCCESS",null,requestId); return get(id);
    }
    @Transactional public Identity update(UUID id,UserUpdate input,Identity actor,String requestId) {
        validate(input.departmentId(),input.roles());
        db.queryForList("select code from roles where code='ADMIN' for update");
        Identity before=get(id);
        if(id.equals(actor.id()) && (!input.active() || !new HashSet<>(before.roles()).equals(input.roles()) || !before.departmentId().equals(input.departmentId())))
            throw ApiException.bad("SELF_CHANGE","不能停用自己或修改自己的角色/院系，请由另一位管理员处理");
        if(before.roles().contains("ADMIN") && (!input.active() || !input.roles().contains("ADMIN")) &&
            db.queryForObject("select count(*) from app_users u join user_roles r on r.user_id=u.id where r.role_code='ADMIN' and u.active",Integer.class)<=1)
            throw ApiException.conflict("必须保留至少一位有效管理员");
        db.update("update app_users set display_name=?,department_id=?,active=? where id=?",input.displayName(),input.departmentId(),input.active(),id);
        db.update("delete from user_roles where user_id=?",id); input.roles().forEach(role -> db.update("insert into user_roles values(?,?)",id,role));
        if(!input.active()) db.update("delete from user_sessions where user_id=?",id);
        audit.local(actor.id(),input.departmentId(),"USER.UPDATE","USER",id.toString(),"SUCCESS",null,requestId); return get(id);
    }
}
