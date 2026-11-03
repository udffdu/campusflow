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
                        if(req.path("permission").asText().equals("maintenance.dispatch")) allowed=token.equals("dispatcher")||token.equals("dispatcher2");
                        if(mode.equals("revoked"))allowed=false;
                        if(mode.equals("scope") && req.has("departmentId")) allowed=false;
                        body=Map.of("allowed",allowed,"identity",Map.of("id",token.equals("B")||token.equals("dispatcher2")?B:A,"departmentId",token.equals("managerB")?DEP_B:DEP,"active",true,"roles",List.of(token.equals("global")||token.equals("adminWithoutPermission")?"ADMIN":"MEMBER")));
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
    @BeforeEach void reset() { mode="ok";db.execute("truncate dispatch_results,creation_results,audit_outbox,work_order_history,work_orders"); }
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
        db.update("insert into work_orders(id,reporter_id,room_id,department_id,resource_version,fault_type,description,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,now())",id,B,ROOM,department,1,"OTHER",text,"PENDING_DISPATCH",java.sql.Timestamp.from(java.time.Instant.now()));return id;
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
        assertThat(fields).containsExactlyInAnyOrder("id","roomId","faultType","description","status","createdAt","assigneeId","dispatcherId","assignedAt","updatedAt","version","history");
        assertThat(detail.path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("description").asText()).hasSize(200);
        assertThat(detail.toString()).doesNotContain("reporterId","departmentId","password","token");
        assertThat(detail.path("history").isArray()).isTrue();assertThat(detail.path("history").size()).isZero();
        mvc.perform(get(MANAGEMENT).cookie(new Cookie("CF_SESSION","manager")).param("status","ASSIGNED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mode="inactive"; // Historical queries do not require the room to remain active.
        mvc.perform(get(MANAGEMENT+"/"+a).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk());
    }
    static volatile List<?> lastCandidateContext=List.of();
    @org.springframework.boot.test.context.TestConfiguration
    static class CandidateConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        AssigneeEligibility eligibility(){return request->{
            if(mode.equals("eligibilityDown"))return new PendingAssigneeEligibility().check(request);
            return new AssigneeEligibility.Decision(request.assigneeId(),request.roomId(),request.departmentId(),!mode.equals("ineligible"),null);
        };}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        AuditTransport audit(){return payload->{if(mode.equals("auditDown"))throw new Failure(503,"AUDIT_UNAVAILABLE");};}

        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        CandidateProvider candidates(){return (actor,room,department,q,page,size)->{
            lastCandidateContext=List.of(actor,room,department,q);
            if(mode.equals("candidateDown"))return new PendingCandidateProvider().search(actor,room,department,q,page,size);
            if(mode.equals("candidateNull"))return null;
            var person=new CandidateProvider.Candidate(B,"Test repairer",!mode.equals("candidateInactive"),mode.equals("candidateUnqualified")?"spaces.write":"maintenance.repair",false,List.of(mode.equals("candidateOutside")?UUID.randomUUID():department));
            if(mode.equals("candidateDuplicate"))return new CandidateProvider.Page(List.of(person,person),page,size,2);
            if(mode.equals("candidateBadTotal"))return new CandidateProvider.Page(List.of(person),page,size,21);
            if(mode.equals("candidateBadPage"))return new CandidateProvider.Page(List.of(person),page+1,size,1);
            if(mode.equals("candidatePaged")){
                var all=java.util.stream.IntStream.rangeClosed(1,21)
                    .mapToObj(i->new CandidateProvider.Candidate(new UUID(0,i),String.format("Repairer %02d",i),true,"maintenance.repair",false,List.of(department)))
                    .filter(p->p.displayName().contains(q)).toList();
                return new CandidateProvider.Page(all.stream().skip((long)page*size).limit(size).toList(),page,size,all.size());
            }
            return new CandidateProvider.Page(mode.equals("candidateEmpty")||page>0?List.of():List.of(person),page,size,mode.equals("candidateEmpty")?0:1);
        };}
    }
    @Test void candidatesScopeEligibilityAndPagination() throws Exception {
        UUID id=seedManaged(DEP,"repair needed");String url=MANAGEMENT+"/"+id+"/candidates";
        mvc.perform(get(url).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isForbidden());
        UUID outside=seedManaged(UUID.randomUUID(),"outside repair");mvc.perform(get(MANAGEMENT+"/"+outside+"/candidates").cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isNotFound());
        var result=node(mvc.perform(get(url).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isOk()));assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(B.toString());assertThat(result.toString()).doesNotContain("password","sessionToken","actorId");
        mvc.perform(get(url).param("page","1").cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.total").value(1));
        for(String m:List.of("candidateInactive","candidateUnqualified","candidateOutside","candidateDown")){mode=m;mvc.perform(get(url).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isServiceUnavailable());}
        mode="candidateEmpty";mvc.perform(get(url).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(url).param("departmentId",DEP.toString()).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isBadRequest());
        assertThatThrownBy(()->new PendingCandidateProvider().search(A,ROOM,DEP,"",0,20)).isInstanceOf(Failure.class).hasMessage("D_CANDIDATES_NOT_READY");
    }
    @Test void candidatesTraverseAllPagesAndUseServerContext() throws Exception {
        UUID id=seedManaged(DEP,"needs repair");String url=MANAGEMENT+"/"+id+"/candidates";mode="candidatePaged";
        var first=node(mvc.perform(get(url).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isOk()));
        var second=node(mvc.perform(get(url).param("page","1").cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isOk()));
        assertThat(first.path("total").asInt()).isEqualTo(21);assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(second.path("total").asInt()).isEqualTo(21);assertThat(second.path("items").size()).isEqualTo(1);
        Set<String> ids=new HashSet<>();first.path("items").forEach(p->ids.add(p.path("id").asText()));second.path("items").forEach(p->ids.add(p.path("id").asText()));assertThat(ids).hasSize(21);
        var filtered=node(mvc.perform(get(url).param("q"," 21 ").cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isOk()));
        assertThat(filtered.path("total").asInt()).isEqualTo(1);assertThat(filtered.path("items").get(0).path("displayName").asText()).isEqualTo("Repairer 21");
        assertThat(lastCandidateContext).isEqualTo(List.of(A,ROOM,DEP,"21"));
        Set<String> fields=new HashSet<>();filtered.path("items").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","displayName","active","qualification","globalScope","departmentIds");
        assertThat(count("work_orders")).isEqualTo(1);
        for(String table:List.of("work_order_history","audit_outbox","creation_results"))assertThat(count(table)).isZero();
    }
    @Test void candidatesRejectMalformedPagesAndQueryOverrides() throws Exception {
        UUID id=seedManaged(DEP,"needs repair");String url=MANAGEMENT+"/"+id+"/candidates";
        for(String m:List.of("candidateNull","candidateDuplicate","candidateBadTotal","candidateBadPage")){
            mode=m;mvc.perform(get(url).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("D_INVALID_CANDIDATES"));
        }
        mode="ok";
        for(var params:List.of(Map.of("actorId",B.toString()),Map.of("page","-1"),Map.of("size","101"),Map.of("q","x".repeat(101)))){
            var req=get(url).cookie(new Cookie("CF_SESSION","dispatcher"));params.forEach(req::param);mvc.perform(req).andExpect(status().isBadRequest());
        }
        mvc.perform(get(url).param("q","one","two").cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isBadRequest());
        mvc.perform(get(url).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isForbidden());
        mode="down";mvc.perform(get(url).cookie(new Cookie("CF_SESSION","dispatcher"))).andExpect(status().isServiceUnavailable());
        assertThat(count("work_orders")).isEqualTo(1);
    }
    @Autowired AuditDelivery delivery;
    ResultActions dispatch(UUID id,String user,String key,UUID assignee,long version) throws Exception {
        return mvc.perform(csrf(post(MANAGEMENT+"/"+id+"/dispatch")).cookie(new Cookie("CF_SESSION",user)).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("assigneeId",assignee,"expectedVersion",version))));
    }
    @Test void dispatchPersistsReplaysAndAuditRecovers() throws Exception {
        UUID id=seedManaged(DEP,"broken lamp");String first=dispatch(id,"dispatcher","same",B,1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(dispatch(id,"dispatcher","same",B,1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(db.queryForObject("select assignee_id from work_orders where id=?",UUID.class,id)).isEqualTo(B);
        assertThat(db.queryForObject("select dispatcher_id from work_orders where id=?",UUID.class,id)).isEqualTo(A);
        assertThat(db.queryForObject("select version from work_orders where id=?",Long.class,id)).isEqualTo(2);
        assertThat(count("work_order_history")).isEqualTo(1);assertThat(count("audit_outbox")).isEqualTo(1);
        assertThat(db.queryForObject("select action from work_order_history",String.class)).isEqualTo("ASSIGNED");
        dispatch(id,"dispatcher","same",A,1).andExpect(status().isConflict());dispatch(id,"dispatcher","new",B,2).andExpect(status().isConflict());
        mvc.perform(get(MANAGEMENT+"/"+id).cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED")).andExpect(jsonPath("$.assigneeId").value(B.toString()));
        UUID event=db.queryForObject("select event_id from audit_outbox",UUID.class);
        mode="auditDown";delivery.deliver();assertThat(db.queryForObject("select status from audit_outbox",String.class)).isEqualTo("RETRY");assertThat(db.queryForObject("select last_error_code from audit_outbox",String.class)).isEqualTo("AUDIT_UNAVAILABLE");
        mode="ok";db.update("update audit_outbox set next_attempt_at=now()");delivery.deliver();assertThat(db.queryForObject("select status from audit_outbox",String.class)).isEqualTo("DELIVERED");assertThat(db.queryForObject("select event_id from audit_outbox",UUID.class)).isEqualTo(event);assertThat(db.queryForObject("select attempts from audit_outbox",Integer.class)).isEqualTo(2);
    }
    @Test void dispatchPermissionScopeAndQualification() throws Exception {
        UUID id=seedManaged(DEP,"broken lamp");dispatch(id,"A","x",B,1).andExpect(status().isForbidden());dispatch(seedManaged(UUID.randomUUID(),"outside lamp"),"dispatcher","x",B,1).andExpect(status().isNotFound());
        mode="ineligible";dispatch(id,"dispatcher","x",B,1).andExpect(status().isConflict());mode="eligibilityDown";dispatch(id,"dispatcher","x",B,1).andExpect(status().isServiceUnavailable());mode="ok";dispatch(id,"dispatcher","x",B,2).andExpect(status().isConflict());
        assertThat(count("dispatch_results")).isZero();assertThat(count("work_order_history")).isZero();assertThat(count("audit_outbox")).isZero();assertThat(db.queryForObject("select status from work_orders where id=?",String.class,id)).isEqualTo("PENDING_DISPATCH");
    }
    @Test void dispatchConcurrentOnlyOneWinner() throws Exception {
        UUID id=seedManaged(DEP,"broken lamp");
        try(var pool=Executors.newFixedThreadPool(2)){var barrier=new CyclicBarrier(2);List<Future<Integer>> futures=new ArrayList<>();for(String user:List.of("dispatcher","dispatcher2"))futures.add(pool.submit(()->{barrier.await();return dispatch(id,user,user,UUID.randomUUID(),1).andReturn().getResponse().getStatus();}));List<Integer> codes=new ArrayList<>();for(var f:futures)codes.add(f.get(20,TimeUnit.SECONDS));assertThat(codes).containsExactlyInAnyOrder(200,409);}
        assertThat(count("work_order_history")).isEqualTo(1);assertThat(count("dispatch_results")).isEqualTo(1);assertThat(count("audit_outbox")).isEqualTo(1);
    }
    @Test void dispatchConcurrentSameKey() throws Exception {
        UUID id=seedManaged(DEP,"broken lamp");try(var pool=Executors.newFixedThreadPool(4)){var barrier=new CyclicBarrier(4);List<Future<String>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{barrier.await();return dispatch(id,"dispatcher","same",B,1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();}));Set<String> results=new HashSet<>();for(var f:futures)results.add(f.get(20,TimeUnit.SECONDS));assertThat(results).hasSize(1);}assertThat(count("work_order_history")).isEqualTo(1);assertThat(count("audit_outbox")).isEqualTo(1);
    }
    @Test void dispatchRollback() throws Exception {
        UUID id=seedManaged(DEP,"broken lamp");for(String table:List.of("work_order_history","audit_outbox","dispatch_results")){
            db.execute("create function fail_dispatch() returns trigger language plpgsql as $$ begin raise exception 'test failure'; end $$");db.execute("create trigger injected before "+(table.equals("dispatch_results")?"UPDATE":"INSERT")+" on "+table+" for each row execute function fail_dispatch()");
            try{dispatch(id,"dispatcher","rollback",B,1).andExpect(status().isInternalServerError());assertThat(count("work_order_history")).isZero();assertThat(count("audit_outbox")).isZero();assertThat(count("dispatch_results")).isZero();assertThat(db.queryForObject("select version from work_orders where id=?",Long.class,id)).isEqualTo(1);}finally{db.execute("drop trigger injected on "+table);db.execute("drop function fail_dispatch()");}
        }
        dispatch(id,"dispatcher","rollback",B,1).andExpect(status().isOk());
    }
    @Test void upgradeExistingV1Data() {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");String url=PG.getJdbcUrl("postgres","postgres");
        try{
            Flyway.configure().dataSource(url,"postgres","").schemas(schema).target("1").load().migrate();
            UUID id=UUID.randomUUID();db.update("insert into "+schema+".work_orders values(?,?,?,?,?,?,?,?,?)",id,A,ROOM,DEP,1,"OTHER","existing repair","PENDING_DISPATCH",java.sql.Timestamp.from(java.time.Instant.now()));
            var upgraded=Flyway.configure().dataSource(url,"postgres","").schemas(schema).load();assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(db.queryForObject("select version from "+schema+".work_orders where id=?",Long.class,id)).isEqualTo(1);assertThat(db.queryForObject("select updated_at=created_at from "+schema+".work_orders where id=?",Boolean.class,id)).isTrue();upgraded.validate();
        }finally{db.execute("drop schema "+schema+" cascade");}
    }
    @Test void dispatchFromRealReportPreservesFormatAndOriginalCreationReplay() throws Exception {
        var created=node(create("A","chain-create",input("broken lamp")).andExpect(status().isCreated()));
        UUID id=UUID.fromString(created.path("id").asText());
        var result=node(dispatch(id,"dispatcher","chain-dispatch",B,1).andExpect(status().isOk()));
        assertThat(result.path("status").asText()).isEqualTo("ASSIGNED");
        var own=node(mvc.perform(get("/api/v1/maintenance/work-orders/"+id).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()));
        assertThat(own.path("version").asInt()).isEqualTo(2);
        assertThat(own.path("assigneeId").asText()).isEqualTo(B.toString());
        assertThat(own.path("dispatcherId").asText()).isEqualTo(A.toString());
        assertThat(own.path("history").size()).isEqualTo(2);
        var assigned=own.path("history").get(1);
        assertThat(assigned.path("action").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.path("fromStatus").asText()).isEqualTo("PENDING_DISPATCH");
        assertThat(assigned.path("toStatus").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.path("actorId").asText()).isEqualTo(A.toString());
        assertThat(assigned.path("assigneeId").asText()).isEqualTo(B.toString());
        assertThat(assigned.path("occurredAt")).isEqualTo(own.path("assignedAt"));
        assertThat(own.path("updatedAt")).isEqualTo(own.path("assignedAt"));
        assertThat(node(create("A","chain-create",input("broken lamp")).andExpect(status().isCreated()))).isEqualTo(created);
        dispatch(id,"dispatcher","chain-dispatch",B,1).andExpect(status().isOk());
        assertThat(count("work_order_history")).isEqualTo(2);assertThat(count("audit_outbox")).isEqualTo(2);
        mode="revoked";dispatch(id,"dispatcher","chain-dispatch",B,1).andExpect(status().isForbidden());
        assertThat(count("dispatch_results")).isEqualTo(1);
    }
    @Test void historyMatchesFormatAndActualFacts() throws Exception {
        var created=node(create("A","history-create",input("broken lamp")));UUID id=UUID.fromString(created.path("id").asText());
        Set<String> fields=new HashSet<>();created.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","reporterId","roomId","departmentId","faultType","description","status","assigneeId","dispatcherId","createdAt","assignedAt","updatedAt","version","history");
        var before=created.path("history").get(0);Set<String> historyFields=new HashSet<>();before.fieldNames().forEachRemaining(historyFields::add);
        assertThat(historyFields).containsExactlyInAnyOrder("id","action","actorId","fromStatus","toStatus","assigneeId","remark","occurredAt");
        assertThat(before.path("action").asText()).isEqualTo("CREATED");assertThat(before.path("fromStatus").isNull()).isTrue();assertThat(before.path("remark").isNull()).isTrue();
        mode="ineligible";dispatch(id,"dispatcher","history-dispatch",B,1).andExpect(status().isConflict());assertThat(count("work_order_history")).isEqualTo(1);
        mode="ok";var assigned=node(dispatch(id,"dispatcher","history-dispatch",B,1).andExpect(status().isOk()));
        dispatch(id,"dispatcher","history-dispatch",B,1).andExpect(status().isOk());
        var history=node(mvc.perform(get("/api/v1/maintenance/work-orders/"+id+"/history").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()));
        assertThat(history.size()).isEqualTo(2);var last=history.get(1);
        assertThat(last.path("action").asText()).isEqualTo("ASSIGNED");assertThat(last.path("fromStatus").asText()).isEqualTo("PENDING_DISPATCH");assertThat(last.path("toStatus").asText()).isEqualTo("ASSIGNED");assertThat(last.path("actorId").asText()).isEqualTo(A.toString());assertThat(last.path("assigneeId").asText()).isEqualTo(B.toString());
        assertThat(java.time.Instant.parse(last.path("occurredAt").asText())).isEqualTo(java.time.Instant.parse(assigned.path("assignedAt").asText()));
        mvc.perform(get("/api/v1/maintenance/work-orders/"+id+"/history").cookie(new Cookie("CF_SESSION","B"))).andExpect(status().isNotFound());
        mvc.perform(get(MANAGEMENT+"/"+id+"/history").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isForbidden());
        UUID outside=seedManaged(UUID.randomUUID(),"outside report");mvc.perform(get(MANAGEMENT+"/"+outside+"/history").cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isNotFound());
        var managed=node(mvc.perform(get(MANAGEMENT+"/"+id+"/history").cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()));assertThat(managed).isEqualTo(history);
        mvc.perform(get("/api/v1/maintenance/work-orders/"+id+"/history").param("actorId",A.toString()).cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isBadRequest());
        var listing=node(mvc.perform(get("/api/v1/maintenance/work-orders").cookie(new Cookie("CF_SESSION","A"))).andExpect(status().isOk()));assertThat(listing.path("items").get(0).has("history")).isFalse();
    }
    @Test void historyStableOrderAndNoMutationRoutes() throws Exception {
        UUID id=UUID.fromString(node(create("A","stable",input("broken lamp"))).path("id").asText());dispatch(id,"dispatcher","stable",B,1).andExpect(status().isOk());
        db.update("update work_order_history set occurred_at=? where work_order_id=?",java.sql.Timestamp.from(java.time.Instant.parse("2026-11-03T00:00:00Z")),id);
        var expected=db.queryForList("select id from work_order_history where work_order_id=? order by occurred_at,id",UUID.class,id);
        var history=node(mvc.perform(get(MANAGEMENT+"/"+id+"/history").cookie(new Cookie("CF_SESSION","manager"))).andExpect(status().isOk()));
        for(int i=0;i<2;i++)assertThat(history.get(i).path("id").asText()).isEqualTo(expected.get(i).toString());
        for(var method:List.of(org.springframework.http.HttpMethod.POST,org.springframework.http.HttpMethod.PUT,org.springframework.http.HttpMethod.DELETE)){
            int status=mvc.perform(csrf(request(method,MANAGEMENT+"/"+id+"/history")).cookie(new Cookie("CF_SESSION","manager")).contentType("application/json").content("{}")).andReturn().getResponse().getStatus();assertThat(status).isEqualTo(405);
        }assertThat(count("work_order_history")).isEqualTo(2);
    }
    @Test void upgradeV2SavedResponsesPreservesOriginalBusinessResult() throws Exception {
        String schema="snap_"+UUID.randomUUID().toString().replace("-","");String url=PG.getJdbcUrl("postgres","postgres");
        try{
            Flyway.configure().dataSource(url,"postgres","").schemas(schema).target("2").load().migrate();
            UUID id=UUID.randomUUID(),history=UUID.randomUUID();String time="2026-11-02T08:00:00Z";
            db.update("insert into "+schema+".work_orders(id,reporter_id,room_id,department_id,resource_version,fault_type,description,status,created_at,updated_at) values(?,?,?,?,1,'OTHER','broken lamp','PENDING_DISPATCH',?::timestamptz,?::timestamptz)",id,A,ROOM,DEP,time,time);
            db.update("insert into "+schema+".work_order_history(id,work_order_id,actor_id,status,occurred_at) values(?,?,?,'PENDING_DISPATCH',?::timestamptz)",history,id,A,time);
            var old=new LinkedHashMap<String,Object>();old.put("id",id);old.put("status","PENDING_DISPATCH");old.put("createdAt",time);old.put("resourceVersion",1);old.put("history",List.of(Map.of("actorId",A,"status","PENDING_DISPATCH","occurredAt",time)));
            db.update("insert into "+schema+".creation_results values(?,?,?,?,?)",A,"key","original-input",id,mapper.writeValueAsString(old));
            UUID assignedHistory=UUID.randomUUID();String assignedTime="2026-11-02T09:00:00Z";
            db.update("update "+schema+".work_orders set status='ASSIGNED',assignee_id=?,dispatcher_id=?,assigned_at=?::timestamptz,updated_at=?::timestamptz,version=2 where id=?",B,A,assignedTime,assignedTime,id);
            db.update("insert into "+schema+".work_order_history(id,work_order_id,actor_id,status,occurred_at,action,from_status,assignee_id) values(?,?,?,'ASSIGNED',?::timestamptz,'ASSIGNED','PENDING_DISPATCH',?)",assignedHistory,id,A,assignedTime,B);
            var dispatched=new LinkedHashMap<String,Object>(old);dispatched.put("status","ASSIGNED");dispatched.put("version",2);dispatched.put("assignedAt",assignedTime);dispatched.put("assigneeId",B);dispatched.put("dispatcherId",A);dispatched.put("updatedAt",assignedTime);dispatched.remove("history");
            db.update("insert into "+schema+".dispatch_results values(?,?,?,?)",A,"dispatch-key","original-dispatch",mapper.writeValueAsString(dispatched));
            var migrated=Flyway.configure().dataSource(url,"postgres","").schemas(schema).load();assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);
            var snapshot=mapper.readTree(db.queryForObject("select response_json from "+schema+".creation_results",String.class));assertThat(snapshot.has("resourceVersion")).isFalse();assertThat(snapshot.path("history").get(0).path("id").asText()).isEqualTo(history.toString());assertThat(snapshot.path("history").get(0).path("toStatus").asText()).isEqualTo("PENDING_DISPATCH");assertThat(snapshot.path("version").asInt()).isEqualTo(1);assertThat(db.queryForObject("select canonical_content from "+schema+".creation_results",String.class)).isEqualTo("original-input");assertThat(migrated.migrate().migrationsExecuted).isZero();
            assertThat(snapshot.path("status").asText()).isEqualTo("PENDING_DISPATCH");assertThat(snapshot.path("assigneeId").isNull()).isTrue();assertThat(snapshot.path("history").size()).isEqualTo(1);
            var savedDispatch=mapper.readTree(db.queryForObject("select response_json from "+schema+".dispatch_results",String.class));assertThat(savedDispatch.path("status").asText()).isEqualTo("ASSIGNED");assertThat(savedDispatch.path("version").asInt()).isEqualTo(2);assertThat(savedDispatch.path("history").size()).isEqualTo(2);assertThat(savedDispatch.path("history").get(1).path("id").asText()).isEqualTo(assignedHistory.toString());
        }finally{db.execute("drop schema "+schema+" cascade");}
    }
    @Test void historyAuthorizationFailsClosedAndNeverWrites() throws Exception {
        UUID id=UUID.fromString(node(create("A","protected-history",input("broken lamp"))).path("id").asText());
        for(String base:List.of("/api/v1/maintenance/work-orders",MANAGEMENT)){
            String path=base+"/"+id+"/history",user=base.equals(MANAGEMENT)?"manager":"A";
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).cookie(new Cookie("CF_SESSION","expired"))).andExpect(status().isUnauthorized());
            mode="revoked";mvc.perform(get(path).cookie(new Cookie("CF_SESSION",user))).andExpect(status().isForbidden());
            mode="down";mvc.perform(get(path).cookie(new Cookie("CF_SESSION",user))).andExpect(status().isServiceUnavailable());
            mode="ok";
            mvc.perform(get(path).cookie(new Cookie("CF_SESSION",user)).param("departmentId",DEP.toString())).andExpect(status().isBadRequest());
            for(var method:List.of(org.springframework.http.HttpMethod.POST,org.springframework.http.HttpMethod.PUT,org.springframework.http.HttpMethod.DELETE)){
                mvc.perform(csrf(request(method,path)).cookie(new Cookie("CF_SESSION",user)).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
            }
        }
        assertThat(count("work_order_history")).isEqualTo(1);assertThat(count("audit_outbox")).isEqualTo(1);
        assertThat(count("dispatch_results")).isZero();
    }
    @Test void G_emptyMigrationAndRepeat() {
        String schema="migration_"+UUID.randomUUID().toString().replace("-","");
        var clean=Flyway.configure().dataSource(PG.getJdbcUrl("postgres","postgres"),"postgres","").schemas(schema).load();
        try { assertThat(clean.migrate().migrationsExecuted).isEqualTo(3);assertThat(clean.migrate().migrationsExecuted).isZero();clean.validate(); }
        finally { db.execute("drop schema "+schema+" cascade"); }
        flyway.validate();
    }
}
