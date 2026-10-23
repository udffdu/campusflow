package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class PlatformController {
    private final IdentityService identities; private final ResourceService resources; private final NotificationService notifications;
    private final AuditService audit; private final Access access; private final JdbcTemplate db; private final boolean secure;
    private final RoleService roleService;
    private final CsrfTokenRepository csrfRepository;
    PlatformController(IdentityService identities,ResourceService resources,NotificationService notifications,AuditService audit,Access access,JdbcTemplate db,RoleService roleService,CsrfTokenRepository csrfRepository,@Value("${platform.cookie-secure}") boolean secure) {
        this.identities=identities;this.resources=resources;this.notifications=notifications;this.audit=audit;this.access=access;this.db=db;this.secure=secure;
        this.roleService=roleService;
        this.csrfRepository=csrfRepository;
    }
    @GetMapping("/health") Map<String,String> health() { db.queryForObject("select 1",Integer.class); return Map.of("status","UP","service","platform"); }
    @GetMapping("/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    private void cookie(HttpServletResponse res,String token,Duration age) {
        res.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("CF_SESSION",token).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(age).build().toString());
    }
    @PostMapping("/auth/login") Identity login(@Valid @RequestBody Login input,HttpServletRequest req,HttpServletResponse res) {
        String token=identities.login(input,ApiErrors.requestId(req)).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","账号或密码不正确、账号已停用或暂时锁定"));
        cookie(res,token,Duration.ofHours(8)); csrfRepository.saveToken(null,req,res); return identities.findSession(token).orElseThrow();
    }
    @GetMapping("/auth/me") Identity me() { return access.user(); }
    @PostMapping("/auth/logout") Map<String,Boolean> logout(HttpServletRequest req,HttpServletResponse res) {
        identities.logout(SecurityConfig.cookie(req,"CF_SESSION"),access.user(),ApiErrors.requestId(req)); cookie(res,"",Duration.ZERO); csrfRepository.saveToken(null,req,res); return Map.of("ok",true);
    }
    @PostMapping("/auth/password") Map<String,Boolean> password(@Valid @RequestBody PasswordChange input,HttpServletRequest req,HttpServletResponse res) {
        identities.password(access.user(),input,ApiErrors.requestId(req)); cookie(res,"",Duration.ZERO); csrfRepository.saveToken(null,req,res); return Map.of("ok",true);
    }
    @GetMapping("/departments") List<Department> departments() {
        access.user(); return db.query("select * from departments order by code",(rs,n)->new Department(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")));
    }
    @GetMapping("/roles") List<Role> roles() {
        access.require("users.manage"); return roleService.list();
    }
    @PostMapping("/departments") @ResponseStatus(HttpStatus.CREATED) Department createDepartment(@Valid @RequestBody DepartmentWrite input,HttpServletRequest req) { return roleService.department(null,input,access.require("users.manage"),ApiErrors.requestId(req)); }
    @PutMapping("/departments/{id}") Department updateDepartment(@PathVariable UUID id,@Valid @RequestBody DepartmentWrite input,HttpServletRequest req) { return roleService.department(id,input,access.require("users.manage"),ApiErrors.requestId(req)); }
    @GetMapping("/permissions") List<Permission> permissions() { access.require("users.manage");return roleService.permissions(); }
    @PostMapping("/roles") @ResponseStatus(HttpStatus.CREATED) Role createRole(@Valid @RequestBody RoleWrite input,HttpServletRequest req) { return roleService.write(input,true,access.require("users.manage"),ApiErrors.requestId(req)); }
    @PutMapping("/roles/{code}") Role updateRole(@PathVariable String code,@Valid @RequestBody RoleWrite input,HttpServletRequest req) {
        if(!code.equals(input.code())) throw ApiException.bad("ROLE","角色编号不可修改");
        return roleService.write(input,false,access.require("users.manage"),ApiErrors.requestId(req));
    }
    @GetMapping("/users") Page<Identity> users(@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { access.require("users.manage");return identities.list(page,size); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) Identity createUser(@Valid @RequestBody UserCreate input,HttpServletRequest req) { return identities.create(input,access.require("users.manage"),ApiErrors.requestId(req)); }
    @PutMapping("/users/{id}") Identity updateUser(@PathVariable UUID id,@Valid @RequestBody UserUpdate input,HttpServletRequest req) { return identities.update(id,input,access.require("users.manage"),ApiErrors.requestId(req)); }
    @GetMapping("/resources") Page<Resource> resources(@RequestParam(required=false) String type,@RequestParam(required=false) UUID parentId,@RequestParam(required=false) @Size(max=100) String q,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { access.require("spaces.read"); return resources.list(type,parentId,q,page,size); }
    @GetMapping("/resources/{id}") Resource resource(@PathVariable UUID id) { access.require("spaces.read"); return resources.get(id); }
    @PostMapping("/resources") @ResponseStatus(HttpStatus.CREATED) Resource createResource(@Valid @RequestBody ResourceCreate input,HttpServletRequest req) { return resources.create(input,access.require("spaces.write"),ApiErrors.requestId(req)); }
    @PutMapping("/resources/{id}") Resource updateResource(@PathVariable UUID id,@Valid @RequestBody ResourceUpdate input,HttpServletRequest req) { return resources.update(id,input,access.require("spaces.write"),ApiErrors.requestId(req)); }
    @GetMapping("/notifications") Page<Notice> inbox(@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return notifications.inbox(access.user().id(),page,size); }
    @GetMapping("/notifications/unread") Map<String,Long> unread() { return Map.of("count",notifications.unread(access.user().id())); }
    @PostMapping("/notifications/{id}/read") Map<String,Boolean> read(@PathVariable UUID id) { notifications.read(access.user().id(),id);return Map.of("ok",true); }
    @PostMapping("/notifications/read-all") Map<String,Boolean> readAll() { notifications.readAll(access.user().id());return Map.of("ok",true); }
    @GetMapping("/deliveries") Page<Delivery> deliveries(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { access.require("notifications.manage");return notifications.tasks(status,page,size); }
    @PostMapping("/deliveries") @ResponseStatus(HttpStatus.ACCEPTED) Delivery announce(@Valid @RequestBody NotificationEvent input,HttpServletRequest req) {
        return notifications.announce(input,access.require("notifications.manage"),ApiErrors.requestId(req));
    }
    @PostMapping("/deliveries/{id}/retry") Map<String,Boolean> retry(@PathVariable UUID id,HttpServletRequest req) { notifications.retry(id,access.require("notifications.manage"),ApiErrors.requestId(req));return Map.of("ok",true); }
    @GetMapping("/audit") Page<Audit> audit(@RequestParam(required=false) String source,@RequestParam(required=false) String outcome,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return audit.list(access.require("audit.read"),source,outcome,page,size); }
    @GetMapping("/overview") Map<String,Object> overview() {
        var user=access.user(); var result=new LinkedHashMap<String,Object>();
        result.put("resources",db.queryForObject("select count(*) from resources",Long.class));
        result.put("rooms",db.queryForObject("select count(*) from resources where type='ROOM'",Long.class));
        result.put("seats",db.queryForObject("select count(*) from resources where type='SEAT'",Long.class));
        result.put("unread",notifications.unread(user.id()));
        result.put("recentResources",resources.list("ROOM",null,null,0,4).items()); return result;
    }
}
