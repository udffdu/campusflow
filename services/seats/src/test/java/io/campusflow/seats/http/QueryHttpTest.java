package io.campusflow.seats.http;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.campusflow.seats.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real M1 TCP/PG with a fault-injection D HTTP server. Real D integration lives in check_m1_http.py. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(25)
class QueryHttpTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final UUID room=UUID.randomUUID(),seat=UUID.randomUUID(),user=UUID.randomUUID(),dept=UUID.randomUUID(),other=UUID.randomUUID();
    private static final String TOKEN="t".repeat(43),KEY="k".repeat(38)+"+/==";
    private EmbeddedPostgres pg;
    private HttpServer upstream;
    private ExecutorService workers;
    private ServletWebServerApplicationContext app,demoApp;
    private String demoBase;
    private String base;
    private HttpClient http;
    private volatile Function<HttpExchange,Reply> responder;
    private final List<String> calls=new CopyOnWriteArrayList<>();
    private record Reply(int status,Object body,long delay) { Reply(Object body){this(200,body,0);} }
    @BeforeAll void start() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);workers=Executors.newCachedThreadPool();upstream.setExecutor(workers);
        upstream.createContext("/",exchange->{
            try {
                assertEquals("Bearer "+KEY,exchange.getRequestHeaders().getFirst("Authorization"));
                calls.add(exchange.getRequestURI().toString());
                Reply reply=responder.apply(exchange);
                byte[] body=reply.body() instanceof byte[] raw?raw:json.writeValueAsBytes(reply.body());exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(reply.status(),body.length);if(reply.delay()>0)Thread.sleep(reply.delay());exchange.getResponseBody().write(body);
            } catch(Exception ignored) {} finally {exchange.close();}
        });upstream.start();
        pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
        app=(ServletWebServerApplicationContext)new SpringApplication(SeatsApplication.class).run(
                "--server.port=0","--spring.datasource.url="+pg.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres",
                "--m1.d-base-url=http://127.0.0.1:"+upstream.getAddress().getPort(),"--m1.service-key="+KEY,
                "--m1.max-advance=PT24H","--m1.max-duration=PT4H","--m1.connect-timeout=PT0.2S",
                "--m1.read-timeout=PT0.6S","--m1.call-budget=PT1S","--m1.max-pages=3");
        base="http://127.0.0.1:"+app.getWebServer().getPort();http=HttpClient.newHttpClient();
        demoApp=(ServletWebServerApplicationContext)new SpringApplication(SeatsApplication.class,DemoFixtureConfiguration.class).run(
                "--server.port=0","--spring.datasource.url="+pg.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres",
                "--m1.d-base-url=http://127.0.0.1:"+upstream.getAddress().getPort(),"--m1.service-key="+KEY,
                "--m1.max-advance=PT24H","--m1.max-duration=PT4H","--m1.connect-timeout=PT0.2S",
                "--m1.read-timeout=PT0.6S","--m1.call-budget=PT1S","--m1.max-pages=3",
                "--M1_DEMO_ORIGIN=http://127.0.0.1:5274","--M1_DEMO_CSRF_SECRET="+Base64.getEncoder().encodeToString(new byte[32]));
        demoBase="http://127.0.0.1:"+demoApp.getWebServer().getPort();
    }
    @AfterAll void stop() throws Exception {if(demoApp!=null)demoApp.close();if(app!=null)app.close();if(pg!=null)pg.close();if(upstream!=null)upstream.stop(0);if(workers!=null)workers.shutdownNow();}
    @BeforeEach void reset() {
        calls.clear();responder=this::normal;
        new JdbcTemplate(pg.getPostgresDatabase()).execute("truncate m1.outbox_events,m1.reservation_requests,m1.reservations");
    }
    private Map<String,Object> identity(boolean allowed,Set<String> permissions) {
        return Map.of("allowed",allowed,"identity",Map.of("id",user,"departmentId",dept,"active",true,"permissions",permissions));
    }
    private Map<String,Object> resource(UUID id,String type,UUID department,String scope,Set<String> features,boolean active) {
        Map<String,Object> r=new HashMap<>(Map.of("id",id,"type",type,"parentId",type.equals("SEAT")?room:UUID.randomUUID(),
                "departmentId",department,"code",type.equals("SEAT")?"S01":"R01","name","测试资源",
                "effectiveActive",active,"active",active,"features",features,"openingHours",List.of(Map.of("day",period().startAt().atZone(ZoneId.of("Asia/Shanghai")).getDayOfWeek().getValue(),"start","00:00","end","23:59"))));
        if(scope!=null)r.put("bookingScope",scope);r.put("version",1);return r;
    }
    private Object roomResource(){return resource(room,"ROOM",dept,"DEPARTMENT",Set.of(),true);}
    private Object seatResource(){return resource(seat,"SEAT",dept,null,Set.of("POWER_OUTLET"),true);}
    private static Map<String,Object> page(List<?> items,int page,long total){return Map.of("items",items,"page",page,"size",100,"total",total);}
    private Reply normal(HttpExchange e) {
        String path=e.getRequestURI().getPath();
        if(path.endsWith("authorization")){
            try {JsonNode input=json.readTree(e.getRequestBody());assertTrue(Set.of("seats.availability.read","seats.reservations.create","seats.reservations.read.own").contains(input.path("permission").asText()));assertEquals(TOKEN,input.path("sessionToken").asText());}catch(Exception ex){throw new RuntimeException(ex);}
            return new Reply(identity(true,Set.of(DQueryClient.PERMISSION,"seats.reservations.create","seats.reservations.read.own")));
        }
        if(path.endsWith(room.toString()))return new Reply(roomResource());
        if(path.endsWith(seat.toString()))return new Reply(seatResource());
        if(e.getRequestURI().getQuery().contains("type=ROOM"))return new Reply(page(List.of(roomResource()),0,1));
        return new Reply(page(List.of(seatResource()),0,1));
    }
    private TimeRange period(){Instant start=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.HOURS);if(start.atZone(ZoneId.of("Asia/Shanghai")).getHour()==23)start=start.plusSeconds(3600);return new TimeRange(start,start.plusSeconds(3600));}
    private String query(){return "/api/m1/v1/seat-candidates?roomId="+room+"&startAt="+period().startAt()+"&endAt="+period().endAt();}
    private HttpResponse<String> get(String path,boolean cookie)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).GET();if(cookie)builder.header("Cookie","CF_SESSION="+TOKEN);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    private void counts(int expected){var jdbc=new JdbcTemplate(pg.getPostgresDatabase());for(String t:List.of("reservations","reservation_requests","outbox_events"))assertEquals(expected,jdbc.queryForObject("select count(*) from m1."+t,Integer.class));}
    @Test void authenticatesAndMapsCandidatesWithoutWrites() throws Exception {
        var response=get(query()+"&requiresPower=true",true);assertEquals(200,response.statusCode(),response.body());var data=body(response);
        assertEquals(seat.toString(),data.at("/items/0/id").asText());assertTrue(data.at("/items/0/hasPowerOutlet").asBoolean());
        assertEquals("CANDIDATE_ONLY",data.path("stage").asText());assertFalse(data.path("canReserve").asBoolean(true));
        assertFalse(response.body().contains(user.toString()));assertEquals(3,calls.size());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(data.has("userId"));counts(0);
    }
    @Test void ownAndOthersOccupancyUseRealStoreAndQueriesDoNotMutate() throws Exception {
        var store=app.getBean(ReservationStore.class);store.create(new CreateReservation(other,room,seat,period(),1),"other-1");counts(1);
        var response=get(query(),true);assertEquals(200,response.statusCode());assertEquals(0,body(response).path("total").asInt());counts(1);
        store.create(new CreateReservation(user,UUID.randomUUID(),UUID.randomUUID(),period(),1),"own-1");
        response=get(query(),true);assertEquals("USER_TIME_CONFLICT",body(response).path("reason").asText());counts(2);
    }
    @Test void noCookieIs401WithoutDCall()throws Exception {assertEquals(401,get(query(),false).statusCode());assertTrue(calls.isEmpty());}
    @Test void invalidSessionIs401()throws Exception {responder=e->new Reply(Map.of("allowed",false));assertEquals(401,get(query(),true).statusCode());}
    @Test void validIdentityWithoutBusinessPermissionIs403()throws Exception {responder=e->new Reply(identity(false,Set.of("spaces.write")));assertEquals(403,get(query(),true).statusCode());assertEquals(1,calls.size());}
    @Test void authorizationIsNotCachedAfterPermissionRevocation()throws Exception {assertEquals(200,get(query(),true).statusCode());responder=e->new Reply(identity(false,Set.of("spaces.read")));assertEquals(403,get(query(),true).statusCode());}
    @Test void explicitDenyCannotBeOverriddenByPermissionSnapshot()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("authorization")?new Reply(identity(false,Set.of(DQueryClient.PERMISSION))):normal(e);assertEquals(403,get(query(),true).statusCode());}
    @Test void trueWithoutIdentityFailsClosed()throws Exception {responder=e->new Reply(Map.of("allowed",true));assertEquals(503,get(query(),true).statusCode());}
    @Test void trueWithoutRequiredPermissionFailsClosed()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("authorization")?new Reply(identity(true,Set.of("spaces.write"))):normal(e);assertEquals(503,get(query(),true).statusCode());}
    @ParameterizedTest @ValueSource(ints={401,403,500,302}) void dTransportFailureIs503NotBrowserLogout(int status)throws Exception {responder=e->new Reply(status,Map.of("detail","must not leak upstream"),0);var response=get(query(),true);assertEquals(503,response.statusCode());assertFalse(response.body().contains("must not leak"));}
    @Test void roomScopeIsEnforcedBeforeReadingSeats()throws Exception {responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(resource(room,"ROOM",other,"DEPARTMENT",Set.of(),true)):normal(e);assertEquals(403,get(query(),true).statusCode());assertEquals(2,calls.size());}
    @Test void campusScopeAllowsCrossDepartment()throws Exception {responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(resource(room,"ROOM",other,"CAMPUS",Set.of(),true)):e.getRequestURI().getPath().endsWith("resources")?new Reply(page(List.of(resource(seat,"SEAT",other,null,Set.of(),true)),0,1)):normal(e);assertEquals(1,body(get(query(),true)).path("total").asInt());}
    @Test void missingMetadataAndUnknownScopeFailClosed()throws Exception {for(String field:List.of("bookingScope","effectiveActive","openingHours","features","departmentId","parentId")){responder=e->{if(!e.getRequestURI().getPath().endsWith(room.toString()))return normal(e);var r=resource(room,"ROOM",dept,"DEPARTMENT",Set.of(),true);r.remove(field);return new Reply(r);};assertEquals(503,get(query(),true).statusCode(),field);}responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(resource(room,"ROOM",dept,"UNKNOWN",Set.of(),true)):normal(e);assertEquals(503,get(query(),true).statusCode());}
    @Test void malformedRoomUuidIsAnUpstreamFaultNotBadBrowserInput()throws Exception {responder=e->{if(!e.getRequestURI().getPath().endsWith(room.toString()))return normal(e);var r=resource(room,"ROOM",dept,"DEPARTMENT",Set.of(),true);r.put("id","not-a-uuid");return new Reply(r);};assertEquals(503,get(query(),true).statusCode());}
    @Test void malformedJsonAndTrailingTokensAreRejected()throws Exception {for(String raw:List.of("{broken", "{\"allowed\":false} {}")){responder=e->new Reply(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(503,get(query(),true).statusCode());}}
    @Test void seatParentOrDepartmentMismatchIs503()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("resources")?new Reply(page(List.of(resource(seat,"SEAT",other,null,Set.of(),true)),0,1)):normal(e);assertEquals(503,get(query(),true).statusCode());}
    @Test void missingRoomIs404AndWrongTypeIs404()throws Exception {responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(404,Map.of(),0):normal(e);assertEquals(404,get(query(),true).statusCode());responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(Map.of("id",room,"type","CAMPUS")):normal(e);assertEquals(404,get(query(),true).statusCode());}
    @Test void powerAndInactiveFiltersProduceNormalEmptyResults()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("resources")?new Reply(page(List.of(resource(seat,"SEAT",dept,null,Set.of("电源"),true)),0,1)):normal(e);assertEquals(0,body(get(query()+"&requiresPower=true",true)).path("total").asInt());responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(resource(room,"ROOM",dept,"DEPARTMENT",Set.of(),false)):normal(e);assertEquals("ROOM_INACTIVE",body(get(query(),true)).path("reason").asText());}
    @Test void roomTotalIsFilteredBeforeBrowserPagination()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("resources")?new Reply(page(List.of(resource(room,"ROOM",other,"DEPARTMENT",Set.of(),true),resource(other,"ROOM",other,"CAMPUS",Set.of(),true)),0,2)):normal(e);var data=body(get("/api/m1/v1/rooms?size=1",true));assertEquals(1,data.path("total").asInt());assertEquals(other.toString(),data.at("/items/0/id").asText());}
    @Test void allDSeatPagesAreLoadedBeforeFilteringAndPaging()throws Exception {
        List<Object> all=new ArrayList<>();for(int i=0;i<101;i++){var r=resource(UUID.randomUUID(),"SEAT",dept,null,i==100?Set.of("POWER_OUTLET"):Set.of(),true);r.put("code",String.format("S%03d",i));all.add(r);}
        responder=e->{if(!e.getRequestURI().getPath().endsWith("resources"))return normal(e);boolean second=e.getRequestURI().getQuery().contains("page=1");return new Reply(page(second?all.subList(100,101):all.subList(0,100),second?1:0,101));};
        var data=body(get(query()+"&requiresPower=true&size=1",true));assertEquals(1,data.path("total").asInt());assertEquals("S100",data.at("/items/0/code").asText());assertEquals(4,calls.size());
    }
    @Test void incompleteOrDuplicatePagesNeverReturnPartialResults()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("resources")?new Reply(page(Collections.nCopies(100,seatResource()),0,101)):normal(e);assertEquals(503,get(query(),true).statusCode());responder=e->e.getRequestURI().getPath().endsWith("resources")?new Reply(page(List.of(seatResource()),0,101)):normal(e);assertEquals(503,get(query(),true).statusCode());}
    @Test void pageLimitAndInvalidNumbersFailClosed()throws Exception {responder=e->e.getRequestURI().getPath().endsWith("resources")?new Reply(page(List.of(),0,301)):normal(e);assertEquals(503,get(query(),true).statusCode());}
    @Test void sharedBudgetIncludesPreviousCalls()throws Exception {responder=e->{var r=normal(e);return new Reply(r.status(),r.body(),450);};long start=System.nanoTime();assertEquals(503,get(query(),true).statusCode());assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1700,"must share the budget across requests");}
    @Test void slowBodyReturns503()throws Exception {responder=e->new Reply(200,identity(true,Set.of(DQueryClient.PERMISSION)),1200);assertEquals(503,get(query(),true).statusCode());}
    @Test void oversizedResponseReturns503()throws Exception {responder=e->new Reply(Map.of("huge","x".repeat(1_100_000)));assertEquals(503,get(query(),true).statusCode());}
    @ParameterizedTest @ValueSource(strings={"&userId=forged","&page=0&page=1","&requiresPower=1","&size=101","&page=-1","&startAt=bad","&roomId=bad"}) void untrustedParametersAre400(String suffix)throws Exception {assertEquals(400,get(query()+suffix,true).statusCode());assertTrue(calls.isEmpty());}
    @Test void localOffsetsAndNonWholeHoursAreRejected()throws Exception {assertEquals(400,get(query().replace(period().startAt().toString(),period().startAt().plusSeconds(60).toString()),true).statusCode());assertEquals(400,get(query().replace("Z","%2B00:00"),true).statusCode());}
    @Test void writesAreDeniedOnEveryPathWithoutCreatingAnything()throws Exception {for(String method:List.of("POST","PUT","PATCH","DELETE")){var r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/m1/v1/reservations")).method(method,HttpRequest.BodyPublishers.ofString("{} ")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(405,r.statusCode());}counts(0);assertTrue(calls.isEmpty());}
    private String csrf()throws Exception {return body(http.send(HttpRequest.newBuilder(URI.create(demoBase+"/api/m1/v1/csrf")).header("Cookie","CF_SESSION="+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString())).path("token").asText();}
    private Map<String,Object> intent(){return Map.of("roomId",room,"seatId",seat,"startAt",period().startAt().toString(),"endAt",period().endAt().toString());}
    private HttpResponse<String> post(Object input,String key,String token,String origin)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(demoBase+"/api/m1/v1/reservations")).header("Cookie","CF_SESSION="+TOKEN).header("Content-Type","application/json");
        if(key!=null)builder.header("Idempotency-Key",key);if(token!=null)builder.header("X-M1-CSRF-Token",token);if(origin!=null)builder.header("Origin",origin);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(input instanceof String raw?raw:json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> demoGet(String path)throws Exception{return http.send(HttpRequest.newBuilder(URI.create(demoBase+path)).header("Cookie","CF_SESSION="+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());}
    @Test void demoCreatesAndReplaysExactlyOneReservationAndEvent()throws Exception {
        String token=csrf();var input=intent();var created=post(input,"demo-create",token,"http://127.0.0.1:5274");assertEquals(201,created.statusCode(),created.body());counts(1);
        var replay=post(input,"demo-create",token,"http://127.0.0.1:5274");assertEquals(200,replay.statusCode(),replay.body());assertTrue(body(replay).path("replayed").asBoolean());
        assertEquals(body(created).path("id"),body(replay).path("id"));assertEquals("PERSONAL_DEMO",body(created).path("stage").asText());assertFalse(body(created).path("productionReady").asBoolean(true));counts(1);
        assertEquals(200,demoGet("/api/m1/v1/reservations/"+body(created).path("id").asText()).statusCode());assertEquals(body(created).path("id"),body(demoGet("/api/m1/v1/reservation-requests/demo-create")).path("id"));
    }
    @Test void replaySkipsLaterResourceDisableAndPastTimeValidation()throws Exception {
        String token=csrf();var input=intent();var created=post(input,"repeat",token,"http://127.0.0.1:5274");assertEquals(201,created.statusCode());
        responder=e->e.getRequestURI().getPath().endsWith("authorization")?normal(e):new Reply(503,Map.of(),0);
        assertEquals(200,post(input,"repeat",token,"http://127.0.0.1:5274").statusCode());counts(1);
        Instant earlier=Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.HOURS);var past=new TimeRange(earlier,earlier.plusSeconds(3600));
        var pastStore=new ReservationStore(pg.getPostgresDatabase(),new ReservationPolicy(Duration.ofHours(24),Duration.ofHours(4),Clock.fixed(earlier.minusSeconds(60),ZoneOffset.UTC)),Clock.systemUTC(),Duration.ofSeconds(1));
        pastStore.create(new CreateReservation(user,room,seat,past,1),"past");
        assertEquals(200,post(Map.of("roomId",room,"seatId",seat,"startAt",past.startAt().toString(),"endAt",past.endAt().toString()),"past",token,"http://127.0.0.1:5274").statusCode());counts(2);
    }
    @Test void sameKeyDifferentIntentIs409WithoutMutatingOriginal()throws Exception {
        String token=csrf();assertEquals(201,post(intent(),"same",token,"http://127.0.0.1:5274").statusCode());var changed=new HashMap<>(intent());changed.put("seatId",other);
        assertEquals(409,post(changed,"same",token,"http://127.0.0.1:5274").statusCode());counts(1);
    }
    @Test void csrfAndOriginFailuresDoNotReachDatabase()throws Exception {
        String token=csrf();assertEquals(403,post(intent(),"csrf",null,"http://127.0.0.1:5274").statusCode());assertEquals(403,post(intent(),"csrf",token,null).statusCode());assertEquals(403,post(intent(),"csrf",token,"http://evil.invalid").statusCode());
        assertEquals(403,post(intent(),"csrf",token.replaceFirst("[0-9]","0"),"http://127.0.0.1:5274").statusCode());counts(0);
    }
    @Test void createDoesNotUseQueryPermissionAsAuthorization()throws Exception {
        String token=csrf();responder=e->e.getRequestURI().getPath().endsWith("authorization")?new Reply(identity(false,Set.of(DQueryClient.PERMISSION))):normal(e);
        assertEquals(403,post(intent(),"query-only",token,"http://127.0.0.1:5274").statusCode());counts(0);
    }
    @Test void lockedAuthorizationRevocationPreventsCreation()throws Exception {
        String token=csrf();var count=new java.util.concurrent.atomic.AtomicInteger();responder=e->e.getRequestURI().getPath().endsWith("authorization")&&count.incrementAndGet()==2?new Reply(identity(false,Set.of(DQueryClient.PERMISSION))):normal(e);
        assertEquals(403,post(intent(),"revoke",token,"http://127.0.0.1:5274").statusCode());counts(0);
    }
    @Test void onlyCreatePermissionCanCreateButCannotUseReadEndpoints()throws Exception {
        String token=csrf();responder=e->{if(!e.getRequestURI().getPath().endsWith("authorization"))return normal(e);try{var input=json.readTree(e.getRequestBody());return new Reply(identity(input.path("permission").asText().equals("seats.reservations.create"),Set.of("seats.reservations.create")));}catch(Exception x){throw new RuntimeException(x);}};
        var created=post(intent(),"create-only",token,"http://127.0.0.1:5274");assertEquals(201,created.statusCode(),created.body());assertEquals(403,demoGet("/api/m1/v1/reservations/"+body(created).path("id").asText()).statusCode());counts(1);
    }
    @Test void ownReadsHideOtherUsersRecordsAndKeys()throws Exception {
        var record=app.getBean(ReservationStore.class).create(new CreateReservation(other,room,seat,period(),1),"other-private");
        assertEquals(404,demoGet("/api/m1/v1/reservations/"+record.id()).statusCode());assertEquals(404,demoGet("/api/m1/v1/reservation-requests/other-private").statusCode());counts(1);
    }
    @Test void changedResourceOrBadVersionRollsBackNewRequest()throws Exception {
        String token=csrf();responder=e->e.getRequestURI().getPath().endsWith(room.toString())?new Reply(resource(room,"ROOM",dept,"DEPARTMENT",Set.of(),false)):normal(e);
        assertEquals(409,post(intent(),"disabled",token,"http://127.0.0.1:5274").statusCode());counts(0);
        responder=e->{if(!e.getRequestURI().getPath().endsWith(room.toString()))return normal(e);var r=resource(room,"ROOM",dept,"DEPARTMENT",Set.of(),true);r.put("version",0);return new Reply(r);};
        assertEquals(503,post(intent(),"version",token,"http://127.0.0.1:5274").statusCode());counts(0);
    }
    @Test void duplicateFieldsAndForgedIdentityAreRejected()throws Exception {
        String token=csrf();var input=new HashMap<>(intent());input.put("userId",other);assertEquals(400,post(input,"forged",token,"http://127.0.0.1:5274").statusCode());
        String raw=json.writeValueAsString(intent());raw=raw.substring(0,raw.length()-1)+",\"roomId\":\""+room+"\"}";
        assertEquals(400,post(raw,"duplicate",token,"http://127.0.0.1:5274").statusCode());assertEquals(400,post(intent(),null,token,"http://127.0.0.1:5274").statusCode());counts(0);
    }
    @Test void capabilitiesDistinguishProductionFromIsolatedDemo()throws Exception {
        assertFalse(body(get("/api/m1/v1/reservation-capabilities",true)).path("canCreateDemoReservation").asBoolean(true));
        assertTrue(body(demoGet("/api/m1/v1/reservation-capabilities")).path("canCreateDemoReservation").asBoolean());
        assertEquals(404,get("/api/m1/v1/csrf",true).statusCode());counts(0);
    }

}
