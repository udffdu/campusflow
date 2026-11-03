package io.campusflow.rooms;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Real D process + PostgreSQL. Only the not-yet-shipped M1 boundary is a fault-injectable fixture. */
final class IntegrationFixture implements AutoCloseable {
    static final String KEY=UUID.randomUUID().toString(),OCC_KEY=UUID.randomUUID().toString(),PASSWORD="Test-"+UUID.randomUUID();
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final EmbeddedPostgres pg;
    final Process platform;
    final HttpServer boundary;
    final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    final int platformPort;
    final Map<String,JsonNode> occupancy=new ConcurrentHashMap<>();
    volatile boolean conflict,failAfterWrite,failEvents,failAuth,failOccupancy,badReceipt;
    int writes;
    IntegrationFixture() {
        try {
            pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
            try(var c=pg.getPostgresDatabase().getConnection();var s=c.createStatement()){s.execute("create database platform");}
            try(var socket=new ServerSocket(0)){platformPort=socket.getLocalPort();}
            Path jar=Path.of(System.getProperty("rooms.test.platform-jar","../platform/target/platform-0.1.0-SNAPSHOT.jar")).toAbsolutePath();
            if(!Files.exists(jar))throw new IllegalStateException("Build services/platform with ./mvnw verify before M2 integration tests");
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",jar.toString());
            String extraMigrations=System.getProperty("rooms.test.extra-platform-migrations");
            if(extraMigrations!=null)builder.command().add("--spring.flyway.locations=classpath:db/migration,filesystem:"+Path.of(extraMigrations).toAbsolutePath());
            var env=builder.environment();env.put("DATABASE_URL",pg.getJdbcUrl("postgres","platform"));env.put("DATABASE_USER","postgres");env.put("DATABASE_PASSWORD","");env.put("BOOTSTRAP_ADMIN_PASSWORD",PASSWORD);env.put("COOKIE_SECURE","false");env.put("PORT",""+platformPort);env.put("M2_SERVICE_KEY",KEY);env.put("M1_SERVICE_KEY","");env.put("M3_SERVICE_KEY","");
            platform=builder.redirectErrorStream(true).redirectOutput(Path.of("target/platform-integration.log").toFile()).start();
            boolean ready=false;
            for(int n=0;n<300;n++){try{if(http.send(HttpRequest.newBuilder(URI.create(platformUrl()+"/api/v1/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200){ready=true;break;}}catch(Exception ignored){}Thread.sleep(100);}
            if(!ready)throw new IllegalStateException("Real D did not start; see target/platform-integration.log");
            // D health can precede its ApplicationRunner; login helper retries bootstrap readiness.
            boundary=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);boundary.setExecutor(executor);
            boundary.createContext("/internal/v1/room-occupancies",this::m1);
            boundary.createContext("/internal/v1/",this::proxyD);boundary.start();
        } catch(Exception ex){throw new IllegalStateException(ex);}
    }
    String platformUrl(){return "http://127.0.0.1:"+platformPort;}
    String boundaryUrl(){return "http://127.0.0.1:"+boundary.getAddress().getPort();}
    void reset(){occupancy.clear();conflict=false;failAfterWrite=false;failEvents=false;failAuth=false;failOccupancy=false;badReceipt=false;writes=0;}
    private void proxyD(HttpExchange exchange) throws IOException {
        if((failEvents&&(exchange.getRequestURI().getPath().endsWith("/notifications")||exchange.getRequestURI().getPath().endsWith("/audit")))||(failAuth&&exchange.getRequestURI().getPath().endsWith("/authorization"))){respond(exchange,503,"{}");return;}
        try {
            var request=HttpRequest.newBuilder(URI.create(platformUrl()+exchange.getRequestURI())).header("Authorization",exchange.getRequestHeaders().getFirst("Authorization")).header("Content-Type","application/json").method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
            var result=http.send(request,HttpResponse.BodyHandlers.ofString());respond(exchange,result.statusCode(),result.body());
        }catch(Exception ex){respond(exchange,503,"{}");}
    }
    private synchronized void m1(HttpExchange exchange) throws IOException {
        if(!("Bearer "+OCC_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))){respond(exchange,401,"{}");return;}
        if(failOccupancy){respond(exchange,503,"{}");return;}
        String id=exchange.getRequestURI().getPath().substring(exchange.getRequestURI().getPath().lastIndexOf('/')+1);
        if(exchange.getRequestMethod().equals("GET")){JsonNode saved=occupancy.get(id);respond(exchange,saved==null?404:200,saved==null?"{}":saved.toString());return;}
        writes++;var input=json.readTree(exchange.getRequestBody());var current=occupancy.get(id);
        if(current==null||input.get("operationVersion").asLong()>current.get("operationVersion").asLong()) {
            var result=json.createObjectNode();result.put("applicationId",id);result.set("roomId",input.get("roomId"));result.set("startAt",input.get("startAt"));result.set("endAt",input.get("endAt"));result.set("operationVersion",input.get("operationVersion"));
            result.put("state",input.get("desiredState").asText().equals("RELEASED")?"RELEASED":conflict?"CONFLICT":"ACTIVE");result.putNull("reasonCode");occupancy.put(id,result);current=result;
        }
        if(failAfterWrite){failAfterWrite=false;respond(exchange,503,"{}");return;}
        if(badReceipt){var result=current.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)result).put("roomId",UUID.randomUUID().toString());respond(exchange,200,result.toString());return;}
        respond(exchange,200,current.toString());
    }
    private void respond(HttpExchange exchange,int status,String body) throws IOException {byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    @Override public void close() throws Exception {boundary.stop(0);executor.close();platform.destroy();platform.waitFor(10,TimeUnit.SECONDS);pg.close();}
}
