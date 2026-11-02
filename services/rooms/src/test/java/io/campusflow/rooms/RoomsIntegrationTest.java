package io.campusflow.rooms;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RoomsIntegrationTest.TestClock.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomsIntegrationTest {
    static final IntegrationFixture F=new IntegrationFixture();
    static final String EIE="10000000-0000-0000-0000-000000000001",SCI="10000000-0000-0000-0000-000000000002";
    static class MutableClock extends Clock {
        volatile Instant time=Instant.now();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time;}
    }
    @TestConfiguration static class TestClock {@Bean @Primary MutableClock testClock(){return new MutableClock();}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->F.pg.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("rooms.platform-url",F::boundaryUrl);r.add("rooms.occupancy-url",F::boundaryUrl);r.add("rooms.platform-key",()->IntegrationFixture.KEY);r.add("rooms.occupancy-key",()->IntegrationFixture.OCC_KEY);r.add("rooms.cookie-secure",()->false);r.add("rooms.workers-enabled",()->false);
    }
    @Autowired ApplicationService service;@Autowired JdbcTemplate db;@Autowired MutableClock clock;@Autowired ObjectMapper json;@Autowired Flyway flyway;
    @LocalServerPort int port;
    record Browser(String session,String csrf,JsonNode identity){}
    record Reply(int status,JsonNode body){}
    Browser admin,applicant,reviewer,approver,member,other,foreign;String room,foreignRoom,building;Instant start,end;
    @BeforeAll void fixtures() throws Exception {
        for(int i=0;i<30;i++){try{admin=login("admin");break;}catch(Exception e){Thread.sleep(100);}}
        assertThat(admin).isNotNull();
        applicant=user("applicant",EIE,List.of("ROOM_APPLICANT","ROOM_REVIEWER","ROOM_APPROVER"));reviewer=user("reviewer",EIE,List.of("ROOM_REVIEWER"));approver=user("approver",EIE,List.of("ROOM_APPROVER"));member=user("member",EIE,List.of("MEMBER"));other=user("other",EIE,List.of("ROOM_APPLICANT"));foreign=user("foreign",SCI,List.of("ROOM_APPLICANT","ROOM_REVIEWER","ROOM_APPROVER"));
        String campus=resource("CAMPUS",null,null).get("id").asText();building=resource("BUILDING",campus,null).get("id").asText();room=resource("ROOM",building,EIE).get("id").asText();foreignRoom=resource("ROOM",building,SCI).get("id").asText();
    }
    @BeforeEach void reset() {
        F.reset();clock.time=Instant.now();db.execute("truncate m2.outbox,m2.application_history,m2.applications");
        start=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1).atTime(10,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();end=start.plusSeconds(7200);
    }
    @AfterAll void close() throws Exception {F.close();}
    String url(){return "http://127.0.0.1:"+port+"/api/v1/room-applications";}
    Reply call(String method,String url,Object body,Browser browser,boolean csrf) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json");
        if(browser!=null){b.header("Cookie","CF_SESSION="+browser.session()+"; XSRF-TOKEN="+browser.csrf());if(csrf)b.header("X-XSRF-TOKEN",browser.csrf());}
        var response=F.http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }
    Reply api(String method,String path,Object body,Browser browser) throws Exception {return call(method,url()+path,body,browser,true);}
    JsonNode ok(Reply reply,int status){assertThat(reply.status()).withFailMessage("%s",reply.body()).isEqualTo(status);return reply.body();}
    Browser login(String name) throws Exception {
        var csrfResponse=F.http.send(HttpRequest.newBuilder(URI.create(F.platformUrl()+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());String csrf=json.readTree(csrfResponse.body()).get("token").asText();
        var request=HttpRequest.newBuilder(URI.create(F.platformUrl()+"/api/v1/auth/login")).header("Cookie","XSRF-TOKEN="+csrf).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username",name,"password",IntegrationFixture.PASSWORD)))).build();
        var response=F.http.send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        String session=response.headers().allValues("Set-Cookie").stream().filter(c->c.startsWith("CF_SESSION=")).findFirst().orElseThrow().split(";",2)[0].substring("CF_SESSION=".length());
        // D resets its CSRF cookie after login; obtain a fresh token for both services.
        var fresh=F.http.send(HttpRequest.newBuilder(URI.create(F.platformUrl()+"/api/v1/auth/csrf")).header("Cookie","CF_SESSION="+session).GET().build(),HttpResponse.BodyHandlers.ofString());
        return new Browser(session,json.readTree(fresh.body()).get("token").asText(),json.readTree(response.body()));
    }
    Browser user(String name,String department,List<String> roles) throws Exception {ok(call("POST",F.platformUrl()+"/api/v1/users",Map.of("username",name,"displayName",name,"departmentId",department,"roles",roles,"password",IntegrationFixture.PASSWORD),admin,true),201);return login(name);}
    JsonNode resource(String type,String parent,String department) throws Exception {
        var data=new LinkedHashMap<String,Object>();data.put("type",type);data.put("parentId",parent);data.put("code","R-"+UUID.randomUUID());data.put("name",type+" integration");data.put("departmentId",department);data.put("capacity",40);data.put("features",List.of());data.put("active",true);
        data.put("openingHours",type.equals("ROOM")?java.util.stream.IntStream.rangeClosed(1,7).mapToObj(day->Map.of("day",day,"start","08:00","end","22:00")).toList():List.of());return ok(call("POST",F.platformUrl()+"/api/v1/resources",data,admin,true),201);
    }
    Map<String,Object> input(){var body=new LinkedHashMap<String,Object>();body.put("requestKey",UUID.randomUUID());body.put("roomId",room);body.put("title","课程讨论");body.put("purpose","六人课程项目评审");body.put("attendees",6);body.put("startAt",start.toString());body.put("endAt",end.toString());return body;}
    JsonNode create() throws Exception {return ok(api("POST","",input(),applicant),201);}
    JsonNode get(JsonNode detail) throws Exception {return ok(api("GET","/"+id(detail),null,applicant),200);}
    String id(JsonNode detail){return detail.get("application").get("id").asText();}
    Map<String,Object> decision(JsonNode detail,String result){return Map.of("expectedVersion",detail.get("application").get("version").asLong(),"step",detail.get("application").get("currentStep").asInt(),"decision",result,"comment","已核对用途与人数");}
    JsonNode approve(JsonNode detail,Browser who) throws Exception {return ok(api("POST","/"+id(detail)+"/decisions",decision(detail,"APPROVE"),who),200);}
    JsonNode processing() throws Exception {return approve(approve(create(),reviewer),approver);}
    void due(){db.update("update m2.applications set next_attempt_at=?",Timestamp.from(clock.instant()));db.update("update m2.outbox set next_attempt_at=?",Timestamp.from(clock.instant()));}
    String state(JsonNode detail){return detail.get("application").get("status").asText();}

    @Test void identityCsrfEligibilityAndDepartmentAreEnforcedThroughRealD() throws Exception {
        assertThat(api("GET","",null,null).status()).isEqualTo(401);
        assertThat(call("POST",url(),input(),applicant,false).status()).isEqualTo(403);
        assertThat(api("POST","",input(),member).status()).isEqualTo(403);
        var cross=input();cross.put("roomId",foreignRoom);assertThat(api("POST","",cross,applicant).status()).isEqualTo(403);
        F.failAuth=true;assertThat(api("POST","",input(),applicant).status()).isEqualTo(503);assertThat(db.queryForObject("select count(*) from m2.applications",Integer.class)).isZero();
    }
    @Test void revokedRoleAppliesToExistingSession() throws Exception {
        var ephemeral=user("revoked"+UUID.randomUUID().toString().substring(0,8),EIE,List.of("ROOM_APPLICANT"));
        ok(call("PUT",F.platformUrl()+"/api/v1/users/"+ephemeral.identity().get("id").asText(),Map.of("displayName","revoked","departmentId",EIE,"roles",List.of("MEMBER"),"active",true),admin,true),200);
        assertThat(api("POST","",input(),ephemeral).status()).isEqualTo(403);
    }
    @Test void validatesHoursCapacityPastAndMalformedFields() throws Exception {
        var body=input();body.put("attendees",41);assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
        body=input();body.put("startAt",start.plusSeconds(60).toString());assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
        body=input();body.put("endAt",start.plusSeconds(9*3600).toString());assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
        body=input();body.put("startAt",start.minusSeconds(4*3600).toString());assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
        body=input();body.put("applicantId",UUID.randomUUID());assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
        body=input();body.put("roomId",UUID.randomUUID());assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
    }
    @Test void duplicateConcurrentSubmissionsHaveOneDurableResultAndRejectChangedPayload() throws Exception {
        var input=input();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->api("POST","",input,applicant));var b=pool.submit(()->api("POST","",input,applicant));assertThat(id(ok(a.get(),201))).isEqualTo(id(ok(b.get(),201)));
        }
        assertThat(db.queryForObject("select count(*) from m2.applications",Integer.class)).isEqualTo(1);assertThat(db.queryForObject("select count(*) from m2.application_history",Integer.class)).isEqualTo(1);
        input.put("title","changed");assertThat(api("POST","",input,applicant).status()).isEqualTo(409);assertThat(api("POST","",input(),applicant).status()).isEqualTo(409);
    }
    @Test void applicationAndOutboxCommitAtomically() throws Exception {
        db.execute("create function m2.inject_failure() returns trigger language plpgsql as $$ begin raise exception 'injected'; end; $$");db.execute("create trigger injected before insert on m2.outbox for each row execute function m2.inject_failure()");
        try{assertThat(api("POST","",input(),applicant).status()).isEqualTo(500);assertThat(db.queryForObject("select count(*) from m2.applications",Integer.class)).isZero();assertThat(db.queryForObject("select count(*) from m2.application_history",Integer.class)).isZero();}
        finally{db.execute("drop trigger injected on m2.outbox");db.execute("drop function m2.inject_failure()");}
    }
    @Test void orderedSeparateReviewersAndPrivateRecordsAreEnforced() throws Exception {
        var app=create();assertThat(api("GET","/"+id(app),null,other).status()).isEqualTo(404);assertThat(api("GET","/"+id(app),null,foreign).status()).isEqualTo(404);
        assertThat(api("POST","/"+id(app)+"/decisions",decision(app,"APPROVE"),approver).status()).isEqualTo(403);
        assertThat(api("POST","/"+id(app)+"/decisions",decision(app,"APPROVE"),applicant).status()).isEqualTo(403);
        var wrong= new LinkedHashMap<>(decision(app,"APPROVE"));wrong.put("step",1);assertThat(api("POST","/"+id(app)+"/decisions",wrong,admin).status()).isEqualTo(409);
        var next=approve(app,admin);assertThat(api("POST","/"+id(next)+"/decisions",decision(next,"APPROVE"),admin).status()).isEqualTo(403);
        assertThat(ok(api("GET","?view=review",null,admin),200).get("total").asInt()).isZero();assertThat(ok(api("GET","?view=review",null,approver),200).get("total").asInt()).isEqualTo(1);
        assertThat(api("POST","/"+id(app)+"/decisions",decision(app,"APPROVE"),reviewer).status()).isEqualTo(409);
    }
    @Test void concurrentDecisionsCannotAdvanceTwoSteps() throws Exception {
        var app=create();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->api("POST","/"+id(app)+"/decisions",decision(app,"APPROVE"),reviewer).status());var b=pool.submit(()->api("POST","/"+id(app)+"/decisions",decision(app,"REJECT"),admin).status());assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(get(app).get("history").size()).isEqualTo(2);assertThat(F.writes).isZero();
    }
    @Test void rejectionAndTimeoutNeverAcquireOccupancy() throws Exception {
        var app=create();var rejected=ok(api("POST","/"+id(app)+"/decisions",decision(app,"REJECT"),reviewer),200);assertThat(state(rejected)).isEqualTo("REJECTED");
        var pending=create();clock.time=start;service.expire();service.expire();assertThat(state(get(pending))).isEqualTo("EXPIRED");assertThat(get(pending).get("history").size()).isEqualTo(2);assertThat(F.writes).isZero();
    }
    @Test void approvalOnlyAfterDurableOccupancyAndLostResponseReconcilesWithoutDuplicateWrite() throws Exception {
        var app=processing();assertThat(state(app)).isEqualTo("PROCESSING");F.failAfterWrite=true;service.reconcile();assertThat(state(get(app))).isEqualTo("PROCESSING");assertThat(F.occupancy.get(id(app)).get("state").asText()).isEqualTo("ACTIVE");
        due();service.reconcile();assertThat(state(get(app))).isEqualTo("APPROVED");assertThat(F.writes).isEqualTo(1);
    }
    @Test void confirmedConflictDoesNotDisplayApproved() throws Exception {var app=processing();F.conflict=true;service.reconcile();assertThat(state(get(app))).isEqualTo("CONFLICT");}
    @Test void malformedReceiptCannotConfirmOccupancy() throws Exception {var app=processing();F.badReceipt=true;service.reconcile();assertThat(state(get(app))).isEqualTo("PROCESSING");assertThat(get(app).get("application").get("lastError").asText()).isEqualTo("INVALID_OCCUPANCY_RECEIPT");}
    @Test void cancellationAfterLostResponseUsesNewVersionAndWaitsForRelease() throws Exception {
        var app=processing();F.failAfterWrite=true;service.reconcile();app=get(app);
        var cancelled=ok(api("POST","/"+id(app)+"/cancel",Map.of("expectedVersion",app.get("application").get("version").asLong(),"comment","活动取消"),applicant),200);assertThat(state(cancelled)).isEqualTo("RELEASING");
        F.failOccupancy=true;service.reconcile();assertThat(state(get(app))).isEqualTo("RELEASING");F.failOccupancy=false;due();service.reconcile();assertThat(state(get(app))).isEqualTo("CANCELLED");assertThat(F.occupancy.get(id(app)).get("operationVersion").asLong()).isEqualTo(2);assertThat(F.occupancy.get(id(app)).get("state").asText()).isEqualTo("RELEASED");
        var stale=Map.of("roomId",room,"startAt",start.toString(),"endAt",end.toString(),"desiredState","ACTIVE","operationVersion",1,"traceId","stale");
        var response=F.http.send(HttpRequest.newBuilder(URI.create(F.boundaryUrl()+"/internal/v1/room-occupancies/"+id(app))).header("Authorization","Bearer "+IntegrationFixture.OCC_KEY).PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(stale))).build(),HttpResponse.BodyHandlers.ofString());assertThat(json.readTree(response.body()).get("state").asText()).isEqualTo("RELEASED");
    }
    @Test void processingTimeoutCreatesReleaseTombstoneEvenWhenCreateWasNeverSent() throws Exception {
        var app=processing();clock.time=start;service.expire();assertThat(state(get(app))).isEqualTo("RELEASING");service.reconcile();assertThat(state(get(app))).isEqualTo("EXPIRED");assertThat(F.occupancy.get(id(app)).get("operationVersion").asLong()).isEqualTo(2);
    }
    @Test void cancelAndFinalDecisionRaceLeavesNoApprovedLeak() throws Exception {
        var app=approve(create(),reviewer);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->api("POST","/"+id(app)+"/decisions",decision(app,"APPROVE"),approver).status());var b=pool.submit(()->api("POST","/"+id(app)+"/cancel",Map.of("expectedVersion",app.get("application").get("version").asLong(),"comment","取消"),applicant).status());assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(Set.of("CANCELLED","PROCESSING")).contains(state(get(app)));assertThat(F.writes).isZero();
    }
    @Test void failedOperationsRemainRecoverableWithScopedManualRetry() throws Exception {
        var app=processing();F.failOccupancy=true;for(int i=0;i<5;i++){due();service.reconcile();}app=get(app);assertThat(app.get("application").get("needsAttention").asBoolean()).isTrue();
        var retry=Map.of("expectedVersion",app.get("application").get("version").asLong(),"comment","恢复连接后重试");assertThat(api("POST","/"+id(app)+"/retry",retry,other).status()).isEqualTo(403);assertThat(api("POST","/"+id(app)+"/retry",retry,foreign).status()).isEqualTo(403);
        ok(api("POST","/"+id(app)+"/retry",retry,approver),200);F.failOccupancy=false;service.reconcile();assertThat(state(get(app))).isEqualTo("APPROVED");
    }
    @Test void actualDReceivesIdempotentAuditAndInboxAfterOutage() throws Exception {
        var app=create();F.failEvents=true;service.dispatch();assertThat(db.queryForObject("select count(*) from m2.outbox where status='SENT'",Integer.class)).isZero();assertThat(state(get(app))).isEqualTo("PENDING");
        F.failEvents=false;due();service.dispatch();assertThat(db.queryForObject("select count(*) from m2.outbox where status='SENT'",Integer.class)).isEqualTo(2);
        db.update("update m2.outbox set status='PENDING'");due();service.dispatch();
        var audit=ok(call("GET",F.platformUrl()+"/api/v1/audit?source=M2&size=100",null,admin,true),200).get("items");int matching=0;for(var row:audit)if(row.get("objectId").asText().equals(id(app)))matching++;assertThat(matching).isEqualTo(1);
        int notices=0;for(int i=0;i<60;i++){var items=ok(call("GET",F.platformUrl()+"/api/v1/notifications?size=100",null,applicant,true),200).get("items");notices=0;for(var row:items)if(row.get("objectId").asText().equals(id(app)))notices++;if(notices==1)break;Thread.sleep(100);}assertThat(notices).isEqualTo(1);
    }
    @Test void stoppedResourceIsRecheckedAndReleaseConfirmedBeforeConflict() throws Exception {
        var fresh=resource("ROOM",building,EIE);var body=input();body.put("roomId",fresh.get("id").asText());
        var app=approve(approve(ok(api("POST","",body,applicant),201),reviewer),approver);
        ok(call("PUT",F.platformUrl()+"/api/v1/resources/"+fresh.get("id").asText(),Map.of("name",fresh.get("name").asText(),"capacity",40,"features",List.of(),"openingHours",json.convertValue(fresh.get("openingHours"),List.class),"active",false,"expectedVersion",fresh.get("version").asLong()),admin,true),200);
        service.reconcile();assertThat(state(get(app))).isEqualTo("CONFLICT");assertThat(F.occupancy.get(id(app)).get("state").asText()).isEqualTo("RELEASED");
        body.put("requestKey",UUID.randomUUID());assertThat(api("POST","",body,applicant).status()).isEqualTo(400);
    }
    @Test void releaseResponseLossIsRecoveredAndCannotCancelAfterUseBegins() throws Exception {
        var app=processing();service.reconcile();app=get(app);assertThat(state(app)).isEqualTo("APPROVED");
        Instant before=clock.instant();clock.time=start;
        assertThat(api("POST","/"+id(app)+"/cancel",Map.of("expectedVersion",app.get("application").get("version").asLong(),"comment","使用开始后取消"),applicant).status()).isEqualTo(409);
        clock.time=before;ok(api("POST","/"+id(app)+"/cancel",Map.of("expectedVersion",app.get("application").get("version").asLong(),"comment","活动取消"),applicant),200);
        F.failAfterWrite=true;service.reconcile();assertThat(state(get(app))).isEqualTo("RELEASING");due();service.reconcile();assertThat(state(get(app))).isEqualTo("CANCELLED");assertThat(F.writes).isEqualTo(2);
    }
    @Test void deadOutboxCanBeRetriedWithoutChangingTheBusinessResult() throws Exception {
        var app=create();F.failEvents=true;for(int i=0;i<8;i++){due();service.dispatch();}
        assertThat(db.queryForObject("select count(*) from m2.outbox where status='DEAD'",Integer.class)).isEqualTo(2);
        app=get(app);ok(api("POST","/"+id(app)+"/retry",Map.of("expectedVersion",app.get("application").get("version").asLong(),"comment","通知服务恢复"),approver),200);
        F.failEvents=false;due();service.dispatch();assertThat(db.queryForObject("select count(*) from m2.outbox where status<>'SENT'",Integer.class)).isZero();assertThat(state(get(app))).isEqualTo("PENDING");
    }
    @Test void approvalAtDeadlineExpiresInsteadOfCallingM1() throws Exception {
        var app=approve(create(),reviewer);clock.time=start;
        assertThat(state(ok(api("POST","/"+id(app)+"/decisions",decision(app,"APPROVE"),approver),200))).isEqualTo("EXPIRED");service.expire();assertThat(F.writes).isZero();
    }
    @Test void migrationsAndAppendOnlyHistoryAreRealDatabaseConstraints() throws Exception {
        var app=create();assertThat(Arrays.stream(flyway.info().applied()).filter(m->m.getVersion()!=null).map(m->m.getVersion().toString()).toList()).containsExactly("1");assertThat(flyway.migrate().migrationsExecuted).isZero();assertThatThrownBy(()->db.update("delete from m2.application_history where application_id=?",UUID.fromString(id(app)))).isInstanceOf(DataAccessException.class);
    }
}
