package io.campusflow.maintenance;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.flywaydb.core.Flyway;

@SpringBootTest
@AutoConfigureMockMvc
class ReportingTest {
    static final UUID A=UUID.randomUUID(),B=UUID.randomUUID(),DEP=UUID.randomUUID(),DEP_B=UUID.randomUUID(),ROOM=UUID.randomUUID();
    static final String KEY=UUID.randomUUID().toString();
    static final ObjectMapper JSON=new ObjectMapper();
    static final EmbeddedPostgres PG=startPg();
    static final HttpServer D=startD();
    static volatile String mode="ok";
    static EmbeddedPostgres startPg() { try { return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start(); } catch(Exception e) { throw new RuntimeException(e); } }
    static HttpServer startD() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/v1",x->{
                int status=200;Object body;
                if(!("Bearer "+KEY).equals(x.getRequestHeaders().getFirst("Authorization"))) { status=401;body=Map.of(); }
                else if(mode.equals("down")) { status=503;body=Map.of(); }
                else if(mode.equals("malformed")) { body=Map.of("allowed",true); }
                else if(x.getRequestURI().getPath().endsWith("authorization")) {
                    var req=JSON.readTree(x.getRequestBody());String token=req.path("sessionToken").asText();
                    if(token.equals("expired")) body=Map.of("allowed",false);
                    else {
                        boolean allowed=!token.equals("denied") && Set.of("maintenance.report","maintenance.read.own").contains(req.path("permission").asText());
                        if(req.path("permission").asText().equals("maintenance.read.scope")) allowed=Set.of("manager","managerB","global").contains(token);
                        if(mode.equals("scope") && req.has("departmentId")) allowed=false;
                        body=Map.of("allowed",allowed,"identity",Map.of("id",token.equals("B")?B:A,"departmentId",token.equals("managerB")?DEP_B:DEP,"active",true,"roles",List.of(token.equals("global")||token.equals("adminWithoutPermission")?"ADMIN":"MEMBER")));
                    }
                } else if(mode.equals("missing")) { status=404;body=Map.of(); }
                else body=Map.of("id",ROOM,"departmentId",mode.equals("scope")?UUID.randomUUID():DEP,"type",mode.equals("seat")?"SEAT":"ROOM","effectiveActive",!mode.equals("inactive"),"version",1);
                byte[] bytes=JSON.writeValueAsBytes(body);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
            });server.start();return server;
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("maintenance.d-url",()->"http://127.0.0.1:"+D.getAddress().getPort());r.add("maintenance.service-key",()->KEY);r.add("maintenance.cookie-secure",()->false);
    }
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired Flyway flyway;
    @BeforeEach void reset() { mode="ok";db.execute("truncate creation_results,audit_outbox,work_order_history,work_orders"); }
    @AfterAll static void close() throws Exception { D.stop(0);PG.close(); }
    MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder req) throws Exception {
        var res=mvc.perform(get("/api/v1/maintenance/csrf")).andExpect(status().isOk()).andReturn().getResponse();var body=mapper.readTree(res.getContentAsString());
        return req.cookie(res.getCookie("M3-XSRF-TOKEN")).header(body.path("headerName").asText(),body.path("token").asText());
    }
    Map<String,Object> input(String description) { return new LinkedHashMap<>(Map.of("roomId",ROOM,"faultType","OTHER","description",description)); }
    ResultActions create(String user,String key,Object body) throws Exception {
        var req=csrf(post("/api/v1/maintenance/work-orders")).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(body));
        if(user!=null) req.cookie(new Cookie("CF_SESSION",user));return mvc.perform(req);
    }
    JsonNode node(ResultActions r) throws Exception { return mapper.readTree(r.andReturn().getResponse().getContentAsString()); }
    long count(String table) { return db.queryForObject("select count(*) from "+table,Long.class); }
    void empty() { for(String table:List.of("work_orders","work_order_history","audit_outbox","creation_results")) assertThat(count(table)).as(table).isZero(); }
    @Test void A_persistence() throws Exception {
        var result=node(create("A","one",input("broken lamp")).andExpect(status().isCreated()));String id=result.path("id").asText();
        assertThat(result.path("reporterId").asText()).isEqualTo(A.toString());assertThat(result.path("departmentId").asText()).isEqualTo(DEP.toString());assertThat(result.path("status").asText()).isEqualTo("PENDING_DISPATCH");assertThat(result.path("history").size()).isEqualTo(1);
        assertThat(db.queryForObject("select reporter_id from work_orders where id=?",UUID.class,UUID.fromString(id))).isEqualTo(A);
        mvc.perform(get("/api/v1/maintenance/work-orders/"+id).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        for(String table:List.of("work_orders","work_order_history","audit_outbox","creation_results")) assertThat(count(table)).isEqualTo(1);
    }
    @Test void B_validation() throws Exception {
        for(String field:List.of("reporterId","actorId","departmentId","status")) { var body=input("broken lamp");body.put(field,"fake");create("A","x",body).andExpect(status().isBadRequest()); }
        for(String desc:List.of("    ","four","x".repeat(1001))) create("A","x",input(desc)).andExpect(status().isBadRequest());
        var wrong=input("broken lamp");wrong.put("faultType","UNKNOWN");create("A","x",wrong).andExpect(status().isBadRequest());
        for(String m:List.of("missing","seat","inactive","scope")) { mode=m;create("A","x",input("broken lamp")).andExpect(status().is(m.equals("scope")?403:400)); }
        empty();mode="ok";
        create("A","five",input("😀".repeat(5))).andExpect(status().isCreated());create("A","max",input("😀".repeat(1000))).andExpect(status().isCreated());
    }
    @Test void C_authAndCsrf() throws Exception {
        for(String user:new String[]{null,"expired","denied"}) { create(user,"x",input("broken lamp")).andExpect(status().is(user!=null&&user.equals("denied")?403:401));
            var req=get("/api/v1/maintenance/work-orders");if(user!=null) req.cookie(new Cookie("CF_SESSION",user));mvc.perform(req).andExpect(status().is(user!=null&&user.equals("denied")?403:401)); }
        for(String m:List.of("down","malformed")) { mode=m;create("A","x",input("broken lamp")).andExpect(status().isServiceUnavailable());mvc.perform(get("/api/v1/maintenance/work-orders").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isServiceUnavailable()); }
        mode="ok";mvc.perform(post("/api/v1/maintenance/work-orders").cookie(new Cookie("CF_SESSION","A")).contentType("application/json").content(mapper.writeValueAsString(input("broken lamp")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/maintenance/work-orders").cookie(new Cookie("CF_SESSION","A"),new Cookie("XSRF-TOKEN","d-token")).header("X-XSRF-TOKEN","d-token").contentType("application/json").content(mapper.writeValueAsString(input("broken lamp")))).andExpect(status().isForbidden());empty();
    }
    @Test void D_ownerIsolation() throws Exception {
        String id=node(create("B","b",input("broken lamp"))).path("id").asText();create("A","a",input("broken desk"));
        mvc.perform(get("/api/v1/maintenance/work-orders").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].reporterId").value(A.toString()));
        mvc.perform(get("/api/v1/maintenance/work-orders/"+id).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/maintenance/work-orders").param("reporterId",B.toString()).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/maintenance/work-orders").param("page","1").param("size","1").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/maintenance/work-orders").param("size","0").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isBadRequest());
    }
    @Test void E_concurrentReplay() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var barrier=new CyclicBarrier(8);List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{barrier.await();return create("A","same",input("broken lamp")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();}));
            var responses=new HashSet<String>();for(var f:futures) responses.add(f.get(20,TimeUnit.SECONDS));assertThat(responses).hasSize(1);
            assertThat(create("A","same",input("  broken lamp  ")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).isEqualTo(responses.iterator().next());
        }
        create("A","same",input("broken desk")).andExpect(status().isConflict());
        for(String table:List.of("work_orders","work_order_history","audit_outbox","creation_results")) assertThat(count(table)).isEqualTo(1);
    }
    @Test void E_concurrentDifferentContent() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);List<Future<Integer>> futures=new ArrayList<>();
            for(String desc:List.of("broken lamp","broken desk")) futures.add(pool.submit(()->{barrier.await();return create("A","race",input(desc)).andReturn().getResponse().getStatus();}));
            List<Integer> codes=new ArrayList<>();for(var f:futures) codes.add(f.get(20,TimeUnit.SECONDS));assertThat(codes).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count("work_orders")).isEqualTo(1);
    }
    @Test void F_rollback() throws Exception {
        for(String table:List.of("work_order_history","audit_outbox","creation_results")) {
            String operation=table.equals("creation_results")?"UPDATE":"INSERT";
            db.execute("create or replace function fail_test() returns trigger language plpgsql as $$ begin raise exception 'injected failure'; end $$");
            db.execute("create trigger injected before "+operation+" on "+table+" for each row execute function fail_test()");
            try { create("A","retry",input("broken lamp")).andExpect(status().isInternalServerError());empty(); }
            finally { db.execute("drop trigger injected on "+table);db.execute("drop function fail_test()"); }
        }
        create("A","retry",input("broken lamp")).andExpect(status().isCreated());
    }
    @Test void finalFormatReflectsPersistedCreation() throws Exception {
        var result=node(create("A","format",input("broken lamp")).andExpect(status().isCreated()));
        Set<String> fields=new HashSet<>();result.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","reporterId","roomId","departmentId",
            "faultType","description","status","assigneeId","dispatcherId","createdAt","assignedAt",
            "updatedAt","version","history");
        UUID id=UUID.fromString(result.path("id").asText());
        assertThat(result.path("version").asLong()).isEqualTo(1);
        assertThat(result.path("updatedAt")).isEqualTo(result.path("createdAt"));
        for(String field:List.of("assigneeId","dispatcherId","assignedAt")) assertThat(result.get(field).isNull()).isTrue();
        var history=result.path("history").get(0);fields.clear();history.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","action","actorId","fromStatus","toStatus","assigneeId","remark","occurredAt");
        assertThat(history.path("action").asText()).isEqualTo("CREATED");
        assertThat(history.path("actorId").asText()).isEqualTo(A.toString());
        assertThat(history.path("toStatus").asText()).isEqualTo("PENDING_DISPATCH");
        for(String field:List.of("fromStatus","assigneeId","remark")) assertThat(history.get(field).isNull()).isTrue();
        assertThat(history.path("occurredAt")).isEqualTo(result.path("createdAt"));
        var row=db.queryForMap("select id,actor_id,status,occurred_at from work_order_history where work_order_id=?",id);
        assertThat(history.path("id").asText()).isEqualTo(row.get("id").toString());
        assertThat(row.get("actor_id")).isEqualTo(A);
        assertThat(java.time.Instant.parse(history.path("occurredAt").asText()))
            .isEqualTo(((java.sql.Timestamp)row.get("occurred_at")).toInstant());
        var detail=node(mvc.perform(get("/api/v1/maintenance/work-orders/"+id).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()));
        assertThat(detail).isEqualTo(result);
        var list=node(mvc.perform(get("/api/v1/maintenance/work-orders").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()));
        assertThat(list.path("items").get(0).has("history")).isFalse();
        var saved=mapper.readTree(db.queryForObject("select response_json from creation_results where work_order_id=?",String.class,id));
        assertThat(saved).isEqualTo(result);
        var event=mapper.readTree(db.queryForObject("select payload from audit_outbox where work_order_id=?",String.class,id));
        assertThat(event.path("action").asText()).isEqualTo("MAINTENANCE.REPORTED");
        assertThat(event.path("objectId").asText()).isEqualTo(id.toString());
        assertThat(event.path("actorId").asText()).isEqualTo(A.toString());
        assertThat(event.has("description")).isFalse();
    }
    @Test void invalidKeysAndControlledFieldsLeaveNoData() throws Exception {
        for(String key:List.of("", "bad key", "中文", "x".repeat(129))) {
            create("A",key,input("broken lamp")).andExpect(status().isBadRequest());
        }
        for(String field:List.of("id","assigneeId","dispatcherId","assignedAt","createdAt","updatedAt","version","history")) {
            var body=input("broken lamp");body.put(field,"forged");
            create("A","controlled",body).andExpect(status().isBadRequest());
        }
        empty();
        create("A","a._:-9",input("broken lamp")).andExpect(status().isCreated());
        create("B","a._:-9",input("broken lamp")).andExpect(status().isCreated());
        assertThat(count("work_orders")).isEqualTo(2);
    }
    static final String MANAGEMENT="/api/v1/maintenance/management/work-orders";
    UUID seedManaged(UUID department,String text) {
        UUID id=UUID.randomUUID();
        db.update("insert into work_orders(id,reporter_id,room_id,department_id,resource_version,fault_type,description,status,created_at) values(?,?,?,?,?,?,?,?,?)",id,B,ROOM,department,1,"OTHER",text,"PENDING_DISPATCH",java.sql.Timestamp.from(java.time.Instant.now()));return id;
    }
    @Test void managementScopePaginationAndPrivacy() throws Exception {
        UUID own=seedManaged(DEP,"private description "+"x".repeat(200));UUID foreign=seedManaged(UUID.randomUUID(),"foreign secret");seedManaged(DEP,"second own report");
        var result=node(mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager")).param("status","PENDING_DISPATCH").param("size","1")).andExpect(status().isOk()));
        assertThat(result.path("total").asInt()).isEqualTo(2);assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("foreign secret","reporterId","actorId","departmentId","history");
        var second=node(mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager")).param("page","1").param("size","1")).andExpect(status().isOk()));
        assertThat(second.path("items").get(0).path("id").asText()).isNotEqualTo(result.path("items").get(0).path("id").asText());
        var detail=node(mvc.perform(get(MANAGEMENT+"/"+own).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()));
        assertThat(detail.path("description").asText()).hasSize(220);assertThat(detail.has("reporterId")).isFalse();assertThat(detail.path("assigneeId").isNull()).isTrue();
        mvc.perform(get(MANAGEMENT+"/"+foreign).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isNotFound());
        mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","global"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get(MANAGEMENT+"/"+foreign).cookie(new Cookie("CF_SESSION","global"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(foreign.toString()));
    }
    @Test void managementDeniedAndInvalidRequests() throws Exception {
        UUID id=seedManaged(DEP,"existing report");
        for(String token:List.of("A","adminWithoutPermission","expired")) {
            int expected=token.equals("expired")?401:403;
            mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION",token))).andExpect(status().is(expected));
            mvc.perform(get(MANAGEMENT+"/"+id).cookie(new Cookie("CF_SESSION",token))).andExpect(status().is(expected));
        }
        mvc.perform(get(MANAGEMENT)).andExpect(status().isUnauthorized());
        for(var params:List.of(Map.of("departmentId",DEP.toString()),Map.of("actorId",A.toString()),Map.of("status","INVALID"),Map.of("page","-1"),Map.of("size","101"))) {
            var req=get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager"));params.forEach(req::param);mvc.perform(req).andExpect(status().isBadRequest());
        }
        mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager")).param("status","PENDING_DISPATCH","PENDING_DISPATCH")).andExpect(status().isBadRequest());
        mode="down";mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isServiceUnavailable());
        assertThat(count("work_orders")).isEqualTo(1);
    }
    @Test void managementEmptyAndStableOrder() throws Exception {
        mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0));
        UUID one=seedManaged(DEP,"first report"),two=seedManaged(DEP,"second report");
        db.update("update work_orders set created_at=?",java.sql.Timestamp.from(java.time.Instant.parse("2026-11-02T00:00:00Z")));
        var expected=db.queryForList("select id from work_orders where department_id=? order by created_at desc,id desc",UUID.class,DEP);
        var response=node(mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()));
        assertThat(response.path("items").get(0).path("id").asText()).isEqualTo(expected.getFirst().toString());
        assertThat(response.path("items").get(1).path("id").asText()).isEqualTo(expected.getLast().toString());
    }
    @Test void managementBothDepartmentsAndMinimalProjection() throws Exception {
        UUID a=seedManaged(DEP,"a".repeat(200)),b=seedManaged(DEP_B,"second department");
        var second=node(mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","managerB"))
            .param("status","PENDING_DISPATCH")).andExpect(status().isOk()));
        assertThat(second.path("total").asInt()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(b.toString());
        mvc.perform(get(MANAGEMENT+"/"+a).cookie(new Cookie("CF_SESSION","managerB"))).andExpect(status().isNotFound());
        var first=node(mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()));
        assertThat(first.path("items").get(0).path("description").asText()).hasSize(120);
        var detail=node(mvc.perform(get(MANAGEMENT+"/"+a).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()));
        Set<String> fields=new HashSet<>();detail.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","roomId","faultType","description","status","createdAt","assigneeId","dispatcherId","assignedAt","updatedAt","version");
        assertThat(detail.path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("description").asText()).hasSize(200);
        assertThat(detail.toString()).doesNotContain("reporterId","departmentId","actorId","history","password","token");
        mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager")).param("status","ASSIGNED")).andExpect(status().isBadRequest());
        mode="inactive"; // Historical queries do not require the room to remain active.
        mvc.perform(get(MANAGEMENT+"/"+a).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk());
    }
    @Test void G_emptyMigrationAndRepeat() {
        String schema="migration_"+UUID.randomUUID().toString().replace("-","");
        var clean=Flyway.configure().dataSource(PG.getJdbcUrl("postgres","postgres"),"postgres","").schemas(schema).load();
        try { assertThat(clean.migrate().migrationsExecuted).isEqualTo(1);assertThat(clean.migrate().migrationsExecuted).isZero();clean.validate(); }
        finally { db.execute("drop schema "+schema+" cascade"); }
        flyway.validate();
    }
}
