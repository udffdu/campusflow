package io.campusflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static io.campusflow.platform.Models.*;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformIntegrationTest {
    static final String PASSWORD="Test-"+UUID.randomUUID();
    static final String M1=UUID.randomUUID().toString(),M2=UUID.randomUUID().toString();
    static final UUID EIE=UUID.fromString("10000000-0000-0000-0000-000000000001"),SCI=UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final EmbeddedPostgres PG=startPostgres();
    static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start(); }
        catch(Exception ex) { throw new IllegalStateException("Real PostgreSQL is required for integration tests",ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",()->"postgres"); registry.add("spring.datasource.password",()->"");
        registry.add("platform.bootstrap-password",()->PASSWORD); registry.add("platform.cookie-secure",()->false);
        registry.add("platform.service-keys.M1",()->M1); registry.add("platform.service-keys.M2",()->M2);
        registry.add("platform.delivery-delay-ms",()->3600000);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper mapper; @Autowired PasswordEncoder encoder;
    @Autowired IdentityService identities; @Autowired NotificationService notifications; @Autowired Flyway flyway;
    UUID adminId; Cookie admin;
    @BeforeEach void reset() throws Exception {
        db.execute("truncate inbox,notification_tasks,audit_events,resources,user_sessions,user_roles,app_users");
        adminId=seed("admin",EIE,"ADMIN"); admin=login("admin");
    }
    UUID seed(String name,UUID department,String...roles) {
        UUID id=UUID.randomUUID();db.update("insert into app_users(id,username,display_name,password_hash,department_id) values(?,?,?,?,?)",id,name,name,encoder.encode(PASSWORD),department);
        for(String role:roles)db.update("insert into user_roles values(?,?)",id,role);return id;
    }
    Cookie login(String name) throws Exception {
        var result=mvc.perform(protectedRequest(post("/api/v1/auth/login")).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return new Cookie("CF_SESSION",result.getResponse().getCookie("CF_SESSION").getValue());
    }
    ResultActions json(MockHttpServletRequestBuilder request,Object body,Cookie cookie) throws Exception {
        return mvc.perform(protectedRequest(request).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    MockHttpServletRequestBuilder protectedRequest(MockHttpServletRequestBuilder request) throws Exception {
        var response=mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var body=mapper.readTree(response.getContentAsString());
        return request.cookie(response.getCookie("XSRF-TOKEN")).header(body.get("headerName").asText(),body.get("token").asText());
    }
    ResultActions internal(String path,Object body,String key) throws Exception {
        return mvc.perform(post("/internal/v1"+path).header("Authorization","Bearer "+key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    JsonNode node(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()); }
    Map<String,Object> resource(String type,String parent,UUID department) {
        var result=new LinkedHashMap<String,Object>();result.put("type",type);result.put("parentId",parent);result.put("code","R-"+UUID.randomUUID());result.put("name",type+" test");result.put("departmentId",department);result.put("capacity",type.equals("SEAT")?1:40);result.put("features",List.of("电源"));result.put("openingHours",List.of());result.put("active",true);return result;
    }
    JsonNode create(Map<String,Object> body,Cookie user) throws Exception { return node(json(post("/api/v1/resources"),body,user).andExpect(status().isCreated())); }
    JsonNode[] hierarchy(UUID department) throws Exception {
        var campus=create(resource("CAMPUS",null,null),admin);var building=create(resource("BUILDING",campus.get("id").asText(),null),admin);
        var room=create(resource("ROOM",building.get("id").asText(),department),admin);return new JsonNode[]{campus,building,room};
    }
    Map<String,Object> update(JsonNode resource,boolean active) { return Map.of("name","updated","capacity",resource.get("capacity").asInt(),"features",List.of(),"openingHours",List.of(),"active",active,"expectedVersion",resource.get("version").asLong()); }

    @Test void browserCsrfLoginCookieAndLogoutAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("username","admin","password",PASSWORD)))).andExpect(status().isForbidden());
        var csrfResponse=mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token=mapper.readTree(csrfResponse.getResponse().getContentAsString()).get("token").asText();
        var logged=mvc.perform(post("/api/v1/auth/login").cookie(csrfResponse.getResponse().getCookie("XSRF-TOKEN")).header("X-XSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("username","admin","password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        assertThat(logged.getResponse().getHeader("Set-Cookie")).contains("HttpOnly","SameSite=Lax");
        assertThat(db.queryForList("select token_hash from user_sessions",String.class)).doesNotContain(admin.getValue());
        json(post("/api/v1/auth/logout"),Map.of(),admin).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(admin)).andExpect(status().isUnauthorized());
    }
    @Test void loginLockoutAndPasswordRevocation() throws Exception {
        for(int i=0;i<5;i++)json(post("/api/v1/auth/login"),Map.of("username","admin","password","wrong"),admin).andExpect(status().isUnauthorized());
        json(post("/api/v1/auth/login"),Map.of("username","admin","password",PASSWORD),admin).andExpect(status().isUnauthorized());
        db.update("update app_users set locked_until=now()-interval '1 minute' where id=?",adminId);
        Cookie fresh=login("admin");
        json(post("/api/v1/auth/password"),Map.of("currentPassword",PASSWORD,"newPassword","Changed-"+UUID.randomUUID()),fresh).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(admin)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("select password_hash from app_users where id=?",String.class,adminId)).startsWith("$2a$");
    }
    @Test void browserCanReuseCsrfAcrossAuthenticatedReadsAndMultipleWrites() throws Exception {
        var protection=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        Cookie csrfCookie=protection.getCookie("XSRF-TOKEN");String token=mapper.readTree(protection.getContentAsString()).get("token").asText();
        var logged=mvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("username","admin","password",PASSWORD)))).andExpect(status().isOk()).andReturn().getResponse();
        Cookie session=logged.getCookie("CF_SESSION");
        assertThat(logged.getCookie("XSRF-TOKEN").getMaxAge()).isZero();
        var refreshed=mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie=refreshed.getCookie("XSRF-TOKEN");token=mapper.readTree(refreshed.getContentAsString()).get("token").asText();
        var overview=mvc.perform(get("/api/v1/overview").cookie(session,csrfCookie)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(overview.getCookie("XSRF-TOKEN")).isNull();
        mvc.perform(post("/api/v1/notifications/read-all").cookie(session,csrfCookie).header("X-XSRF-TOKEN",token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications/read-all").cookie(session,csrfCookie).header("X-XSRF-TOKEN",token)).andExpect(status().isOk());
    }
    @Test void databaseDeliveryFailureRollsBackTheBatchAndRecovers() {
        notifications.receive("M1",event(adminId));
        db.execute("create function test_delivery_failure() returns trigger language plpgsql as $$ begin raise exception 'injected storage failure'; end; $$");
        db.execute("create trigger injected_failure before insert on inbox for each row execute function test_delivery_failure()");
        try {
            assertThatThrownBy(notifications::deliver).isInstanceOf(DataAccessException.class);
            assertThat(db.queryForObject("select count(*) from inbox",Integer.class)).isZero();
            assertThat(db.queryForObject("select status from notification_tasks",String.class)).isEqualTo("PENDING");
        } finally { db.execute("drop trigger injected_failure on inbox");db.execute("drop function test_delivery_failure()"); }
        notifications.deliver();assertThat(db.queryForObject("select status from notification_tasks",String.class)).isEqualTo("DELIVERED");
    }
    @Test void adminAnnouncementAndItsAuditCommitAtomically() throws Exception {
        db.execute("create function test_audit_failure() returns trigger language plpgsql as $$ begin raise exception 'injected audit storage failure'; end; $$");
        db.execute("create trigger injected_audit_failure before insert on audit_events for each row execute function test_audit_failure()");
        try {
            json(post("/api/v1/deliveries"),event(adminId),admin).andExpect(status().isInternalServerError());
            assertThat(db.queryForObject("select count(*) from notification_tasks",Integer.class)).isZero();
        } finally { db.execute("drop trigger injected_audit_failure on audit_events");db.execute("drop function test_audit_failure()"); }
        json(post("/api/v1/deliveries"),event(adminId),admin).andExpect(status().isAccepted());
        assertThat(db.queryForObject("select count(*) from notification_tasks",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from audit_events where action='NOTIFICATION.CREATE'",Integer.class)).isEqualTo(1);
    }
    @Test void memberCannotManageAccountsOrAuditOrResources() throws Exception {
        seed("member",EIE,"MEMBER");Cookie member=login("member");
        mvc.perform(get("/api/v1/resources").cookie(member)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users").cookie(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").cookie(member)).andExpect(status().isForbidden());
        json(post("/api/v1/resources"),resource("CAMPUS",null,null),member).andExpect(status().isForbidden());
    }
    @Test void administratorsCanCreateAccountsAndDepartmentsWithoutLeakingPasswords() throws Exception {
        var department=node(json(post("/api/v1/departments"),new DepartmentWrite("LIB","图书馆"),admin).andExpect(status().isCreated()));
        UUID depId=UUID.fromString(department.get("id").asText());
        var created=node(json(post("/api/v1/users"),new UserCreate("new.member","新成员",depId,Set.of("MEMBER"),PASSWORD),admin).andExpect(status().isCreated()));
        assertThat(created.has("password")).isFalse();assertThat(created.has("passwordHash")).isFalse();
        Cookie member=login("new.member");mvc.perform(get("/api/v1/auth/me").cookie(member)).andExpect(jsonPath("$.departmentId").value(depId.toString()));
        json(post("/api/v1/users"),new UserCreate("new.member","重复账号",depId,Set.of("MEMBER"),PASSWORD),admin).andExpect(status().isConflict());
        json(put("/api/v1/departments/"+depId),new DepartmentWrite("CHANGED","重命名"),admin).andExpect(status().isBadRequest());
    }
    @Test void departmentScopeAndRoleChangesApplyToExistingSession() throws Exception {
        UUID id=seed("manager",EIE,"SPACE_MANAGER");Cookie manager=login("manager");var nodes=hierarchy(SCI);
        json(put("/api/v1/resources/"+nodes[2].get("id").asText()),update(nodes[2],false),manager).andExpect(status().isForbidden());
        create(resource("ROOM",nodes[1].get("id").asText(),EIE),manager);
        json(post("/api/v1/resources"),resource("BUILDING",nodes[0].get("id").asText(),null),manager).andExpect(status().isForbidden());
        json(put("/api/v1/users/"+id),new UserUpdate("manager",EIE,Set.of("MEMBER"),true),admin).andExpect(status().isOk());
        json(post("/api/v1/resources"),resource("ROOM",nodes[1].get("id").asText(),EIE),manager).andExpect(status().isForbidden());
    }
    @Test void disabledUserSessionAndSelfDemotionAreRejected() throws Exception {
        UUID id=seed("disabled",EIE,"MEMBER");Cookie user=login("disabled");
        json(put("/api/v1/users/"+id),new UserUpdate("disabled",EIE,Set.of("MEMBER"),false),admin).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(user)).andExpect(status().isUnauthorized());
        json(put("/api/v1/users/"+adminId),new UserUpdate("admin",EIE,Set.of("MEMBER"),true),admin).andExpect(status().isBadRequest());
    }
    @Test void hierarchyOpenHoursAndSeatScopeAreValidated() throws Exception {
        var nodes=hierarchy(EIE);
        json(post("/api/v1/resources"),resource("ROOM",nodes[0].get("id").asText(),EIE),admin).andExpect(status().isBadRequest());
        json(post("/api/v1/resources"),resource("SEAT",nodes[2].get("id").asText(),SCI),admin).andExpect(status().isBadRequest());
        var bad=resource("ROOM",nodes[1].get("id").asText(),EIE);bad.put("openingHours",List.of(Map.of("day",1,"start","08:00","end","12:00"),Map.of("day",1,"start","11:00","end","13:00")));
        json(post("/api/v1/resources"),bad,admin).andExpect(status().isBadRequest());
        var seat=resource("SEAT",nodes[2].get("id").asText(),EIE);seat.put("capacity",2);json(post("/api/v1/resources"),seat,admin).andExpect(status().isBadRequest());
    }
    @Test void concurrentEditsHaveOneWinnerAndDisabledAncestorsPreserveHistory() throws Exception {
        var nodes=hierarchy(EIE);String path="/api/v1/resources/"+nodes[2].get("id").asText();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->json(put(path),update(nodes[2],true),admin).andReturn().getResponse().getStatus());
            var b=executor.submit(()->json(put(path),update(nodes[2],true),admin).andReturn().getResponse().getStatus());
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        json(put("/api/v1/resources/"+nodes[0].get("id").asText()),update(nodes[0],false),admin).andExpect(status().isOk());
        mvc.perform(get(path).cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.effectiveActive").value(false));
        json(post("/api/v1/resources"),resource("SEAT",nodes[2].get("id").asText(),EIE),admin).andExpect(status().isBadRequest());
    }
    NotificationEvent event(UUID recipient) { return new NotificationEvent("event-1",recipient,"审批完成","请查看申请详情","ROOM_APPLICATION","application-1"); }
    @Test void concurrentDuplicateNotificationsCreateOneTaskAndOneInboxMessage() throws Exception {
        var input=event(adminId);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var work=new ArrayList<Future<Integer>>();for(int i=0;i<8;i++)work.add(executor.submit(()->internal("/notifications",input,M1).andReturn().getResponse().getStatus()));
            for(var result:work)assertThat(result.get()).isEqualTo(202);
        }
        assertThat(db.queryForObject("select count(*) from notification_tasks",Integer.class)).isEqualTo(1);
        notifications.deliver();notifications.deliver();assertThat(db.queryForObject("select count(*) from inbox",Integer.class)).isEqualTo(1);
        var changed=new NotificationEvent(input.eventId(),adminId,"不同内容",input.body(),input.objectType(),input.objectId());internal("/notifications",changed,M1).andExpect(status().isConflict());
    }
    @Test void notificationRetryAndInboxOwnershipAreEnforced() throws Exception {
        UUID user=seed("recipient",EIE,"MEMBER");db.update("update app_users set active=false where id=?",user);
        String id=node(internal("/notifications",event(user),M1).andExpect(status().isAccepted())).get("id").asText();
        for(int i=0;i<5;i++){db.update("update notification_tasks set next_attempt_at=now() where id=?",UUID.fromString(id));notifications.deliver();}
        assertThat(db.queryForObject("select status from notification_tasks where id=?",String.class,UUID.fromString(id))).isEqualTo("DEAD");
        db.update("update app_users set active=true where id=?",user);
        json(post("/api/v1/deliveries/"+id+"/retry"),Map.of(),admin).andExpect(status().isOk());notifications.deliver();
        json(post("/api/v1/notifications/"+id+"/read"),Map.of(),admin).andExpect(status().isNotFound());
        Cookie recipient=login("recipient");mvc.perform(get("/api/v1/notifications/unread").cookie(recipient)).andExpect(jsonPath("$.count").value(1));
        json(post("/api/v1/notifications/"+id+"/read"),Map.of(),recipient).andExpect(status().isOk());mvc.perform(get("/api/v1/notifications/unread").cookie(recipient)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/internal/v1/notifications/"+id).header("Authorization","Bearer "+M2)).andExpect(status().isNotFound());
    }
    @Test void serviceIdentityCannotBeForgedAndIntrospectionChecksLiveScope() throws Exception {
        internal("/authorization",new Authorization(admin.getValue(),"users.manage",SCI),"wrong").andExpect(status().isUnauthorized());
        internal("/authorization",new Authorization(admin.getValue(),"users.manage",SCI),M1).andExpect(jsonPath("$.allowed").value(true));
        seed("manager",EIE,"SPACE_MANAGER");Cookie manager=login("manager");
        internal("/authorization",new Authorization(manager.getValue(),"spaces.write",SCI),M1).andExpect(jsonPath("$.allowed").value(false));
        var forged=mapper.convertValue(event(adminId),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});forged.put("source","D");internal("/notifications",forged,M1).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/resources").cookie(admin)).andExpect(status().isUnauthorized());
        json(post("/api/v1/auth/logout"),Map.of(),admin).andExpect(status().isOk());internal("/authorization",new Authorization(admin.getValue(),"users.manage",EIE),M1).andExpect(jsonPath("$.allowed").value(false));
    }
    @Test void auditDeduplicatesRejectsSecretsAndIsImmutableAndScoped() throws Exception {
        UUID auditor=seed("auditor",SCI,"AUDITOR");var input=new AuditEvent("audit-1",auditor,SCI,"WORK_ORDER.ACCEPT","WORK_ORDER","work-1","SUCCESS",null,Instant.now());
        String id=node(internal("/audit",input,M1).andExpect(status().isOk())).get("id").asText();
        internal("/audit",input,M1).andExpect(jsonPath("$.id").value(id));
        var secret=mapper.convertValue(input,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});secret.put("password","must-not-be-stored");internal("/audit",secret,M1).andExpect(status().isBadRequest());
        Cookie user=login("auditor");var records=node(mvc.perform(get("/api/v1/audit").cookie(user)).andExpect(status().isOk())).get("items");
        assertThat(records.size()).isGreaterThanOrEqualTo(1);for(var row:records)assertThat(row.get("departmentId").asText()).isEqualTo(SCI.toString());
        assertThatThrownBy(()->db.update("update audit_events set action='TAMPER' where id=?",UUID.fromString(id))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update("delete from audit_events where id=?",UUID.fromString(id))).isInstanceOf(DataAccessException.class);
    }
    @Test void migrationsCanReplayWithoutChangingAppliedHistory() {
        assertThat(flyway.info().applied()).hasSize(3);assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void customRolesCannotGrantGlobalPrivilegesAndChangesAreImmediate() throws Exception {
        json(post("/api/v1/roles"),new RoleWrite("LIBRARIAN","图书馆空间维护",Set.of("spaces.read","spaces.write")),admin).andExpect(status().isCreated());
        seed("custom",EIE,"LIBRARIAN");Cookie user=login("custom");
        mvc.perform(get("/api/v1/auth/me").cookie(user)).andExpect(jsonPath("$.permissions").isArray());
        json(put("/api/v1/roles/LIBRARIAN"),new RoleWrite("LIBRARIAN","图书馆只读",Set.of("spaces.read")),admin).andExpect(status().isOk());
        assertThat(identities.findSession(user.getValue()).orElseThrow().permissions()).doesNotContain("spaces.write");
        json(post("/api/v1/roles"),new RoleWrite("ESCALATED","越权角色",Set.of("users.manage")),admin).andExpect(status().isBadRequest());
        json(put("/api/v1/roles/ADMIN"),new RoleWrite("ADMIN","override",Set.of("spaces.read")),admin).andExpect(status().isBadRequest());
    }

    @Test void seatPermissionsRequireExplicitAssignmentAndApplyToExistingSessions() throws Exception {
        UUID id=seed("seat-member",EIE,"MEMBER");Cookie user=login("seat-member");
        var permissions=List.of("seats.availability.read","seats.reservations.create","seats.reservations.read.own");
        for(String permission:permissions) {
            internal("/authorization",Map.of("sessionToken",user.getValue(),"permission",permission),M1)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
            internal("/authorization",Map.of("sessionToken",admin.getValue(),"permission",permission),M1)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        }
        json(put("/api/v1/users/"+id),new UserUpdate("seat-member",EIE,Set.of("MEMBER","SEAT_USER"),true),admin)
                .andExpect(status().isOk());
        for(String permission:permissions) {
            internal("/authorization",Map.of("sessionToken",user.getValue(),"permission",permission),M1)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        }
        assertThat(identities.findSession(user.getValue()).orElseThrow().permissions()).doesNotContain("spaces.write");
        json(put("/api/v1/users/"+id),new UserUpdate("seat-member",EIE,Set.of("MEMBER"),true),admin).andExpect(status().isOk());
        internal("/authorization",Map.of("sessionToken",user.getValue(),"permission","seats.reservations.create"),M1)
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
    }

    @Test void seatRoleIsProtectedAndCustomRolesCanSelectRegisteredSeatPermissions() throws Exception {
        json(put("/api/v1/roles/SEAT_USER"),new RoleWrite("SEAT_USER","override",Set.of("spaces.write")),admin)
                .andExpect(status().isBadRequest());
        json(post("/api/v1/roles"),new RoleWrite("SEAT_READER","预约只读",Set.of("seats.reservations.read.own")),admin)
                .andExpect(status().isCreated());
        seed("seat-reader",EIE,"SEAT_READER");Cookie user=login("seat-reader");
        internal("/authorization",Map.of("sessionToken",user.getValue(),"permission","seats.reservations.read.own"),M1)
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        internal("/authorization",Map.of("sessionToken",user.getValue(),"permission","seats.reservations.create"),M1)
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
    }

    @Test void roomScopeSurvivesLegacyUpdatesAndIsVisibleThroughInternalApi() throws Exception {
        var nodes=hierarchy(EIE);var room=nodes[2];String path="/api/v1/resources/"+room.get("id").asText();
        assertThat(room.get("bookingScope").asText()).isEqualTo("DEPARTMENT");
        var body=new LinkedHashMap<>(update(room,true));body.put("bookingScope","CAMPUS");
        var campus=node(json(put(path),body,admin).andExpect(status().isOk()));
        assertThat(campus.get("version").asLong()).isGreaterThan(room.get("version").asLong());
        assertThat(campus.get("departmentId").asText()).isEqualTo(EIE.toString());
        var legacy=node(json(put(path),update(campus,true),admin).andExpect(status().isOk()));
        assertThat(legacy.get("bookingScope").asText()).isEqualTo("CAMPUS");
        var explicitNull=new LinkedHashMap<>(update(legacy,true));explicitNull.put("bookingScope",null);
        json(put(path),explicitNull,admin).andExpect(status().isOk()).andExpect(jsonPath("$.bookingScope").value("CAMPUS"));
        json(put(path),body,admin).andExpect(status().isConflict());
        mvc.perform(get("/internal/v1/resources/"+room.get("id").asText()).header("Authorization","Bearer "+M1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingScope").value("CAMPUS"));
        seed("scope-manager",SCI,"SPACE_MANAGER");Cookie manager=login("scope-manager");
        json(put(path),update(legacy,true),manager).andExpect(status().isForbidden());
    }

    @Test void roomScopeCanBeChosenOnCreateAndRejectsWrongResourceTypes() throws Exception {
        var nodes=hierarchy(EIE);
        var body=resource("ROOM",nodes[1].get("id").asText(),EIE);body.put("bookingScope","CAMPUS");
        assertThat(create(body,admin).get("bookingScope").asText()).isEqualTo("CAMPUS");
        body.put("bookingScope","UNKNOWN");json(post("/api/v1/resources"),body,admin).andExpect(status().isBadRequest());
        var seat=resource("SEAT",nodes[2].get("id").asText(),EIE);seat.put("bookingScope","DEPARTMENT");
        json(post("/api/v1/resources"),seat,admin).andExpect(status().isBadRequest());
        var updateBuilding=new LinkedHashMap<>(update(nodes[1],true));updateBuilding.put("bookingScope","CAMPUS");
        json(put("/api/v1/resources/"+nodes[1].get("id").asText()),updateBuilding,admin).andExpect(status().isBadRequest());
    }

    @Test void legacyNullScopeIsConservativeAndDatabaseRejectsInvalidScopes() throws Exception {
        var nodes=hierarchy(EIE);UUID id=UUID.fromString(nodes[2].get("id").asText());
        db.update("update resources set booking_scope=null where id=?",id);
        mvc.perform(get("/api/v1/resources/"+id).cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingScope").value("DEPARTMENT"));
        assertThatThrownBy(()->db.update("update resources set booking_scope='UNKNOWN' where id=?",id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update("update resources set booking_scope='CAMPUS' where id=?",
                UUID.fromString(nodes[1].get("id").asText()))).isInstanceOf(DataAccessException.class);
    }

    @Test void powerOutletMarkerBelongsToIndividualSeatsAndRoundTrips() throws Exception {
        var nodes=hierarchy(EIE);var seat=resource("SEAT",nodes[2].get("id").asText(),EIE);
        seat.put("features",List.of("POWER_OUTLET","靠窗"));var created=create(seat,admin);
        mvc.perform(get("/internal/v1/resources/"+created.get("id").asText()).header("Authorization","Bearer "+M1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.features[0]").value("POWER_OUTLET"));
        var room=new LinkedHashMap<>(update(nodes[2],true));room.put("features",List.of("POWER_OUTLET"));
        json(put("/api/v1/resources/"+nodes[2].get("id").asText()),room,admin).andExpect(status().isBadRequest());
        var changed=new LinkedHashMap<>(update(created,true));changed.put("features",List.of("靠窗"));
        json(put("/api/v1/resources/"+created.get("id").asText()),changed,admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.features.length()").value(1));
    }

    @Test void v2DatabaseUpgradesWithoutGrantingRolesOrGuessingOutletLabels() {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        var dataSource=new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(PG.getJdbcUrl("postgres","postgres"));dataSource.setCurrentSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
            var legacy=new JdbcTemplate(dataSource);UUID campus=UUID.randomUUID(),building=UUID.randomUUID(),room=UUID.randomUUID(),seat=UUID.randomUUID(),user=UUID.randomUUID();
            legacy.update("insert into resources(id,type,parent_id,code,name,department_id,capacity) values(?,'CAMPUS',null,'C','campus',null,0)",campus);
            legacy.update("insert into resources(id,type,parent_id,code,name,department_id,capacity) values(?,'BUILDING',?,'B','building',null,0)",building,campus);
            legacy.update("insert into resources(id,type,parent_id,code,name,department_id,capacity) values(?,'ROOM',?,'R','room',?,1)",room,building,EIE);
            legacy.update("insert into resources(id,type,parent_id,code,name,department_id,capacity,features) values(?,'SEAT',?,'S','seat',?,1,'[\"电源\"]'::jsonb)",seat,room,EIE);
            legacy.update("insert into app_users(id,username,display_name,password_hash,department_id) values(?,'legacy','legacy','unused-test-hash',?)",user,EIE);
            legacy.update("insert into user_roles values(?,'MEMBER')",user);
            long version=legacy.queryForObject("select version from resources where id=?",Long.class,room);
            var upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(legacy.queryForObject("select booking_scope from resources where id=?",String.class,room)).isEqualTo("DEPARTMENT");
            assertThat(legacy.queryForObject("select version from resources where id=?",Long.class,room)).isGreaterThan(version);
            assertThat(legacy.queryForList("select role_code from user_roles where user_id=?",String.class,user)).containsExactly("MEMBER");
            assertThat(legacy.queryForObject("select features::text from resources where id=?",String.class,seat)).isEqualTo("[\"电源\"]");
            // An older D binary can still insert a room without knowing the new column.
            legacy.update("insert into resources(id,type,parent_id,code,name,department_id,capacity) values(?,'ROOM',?,'OLD','old writer',?,1)",UUID.randomUUID(),building,EIE);
        } finally { db.execute("drop schema if exists "+schema+" cascade"); }
    }
}
