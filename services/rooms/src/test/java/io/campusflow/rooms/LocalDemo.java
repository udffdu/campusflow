package io.campusflow.rooms;

import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.SpringApplication;

/** Opt-in disposable demo: real D + M2; deliberately no simulated M1 approvals. */
public class LocalDemo {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static String session,csrf;
    public static void main(String[] args) throws Exception {
        String password=System.getenv("BOOTSTRAP_ADMIN_PASSWORD");
        if(password==null||password.length()<12)throw new IllegalArgumentException("Set BOOTSTRAP_ADMIN_PASSWORD with at least 12 characters");
        var pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
        try(var c=pg.getPostgresDatabase().getConnection();var s=c.createStatement()){s.execute("create database platform");}
        String key=UUID.randomUUID().toString();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",Path.of("../platform/target/platform-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString(),"--server.address=127.0.0.1");
        var env=builder.environment();env.put("PORT","8080");env.put("DATABASE_URL",pg.getJdbcUrl("postgres","platform"));env.put("DATABASE_USER","postgres");env.put("DATABASE_PASSWORD","");env.put("BOOTSTRAP_ADMIN_PASSWORD",password);env.put("M2_SERVICE_KEY",key);env.put("COOKIE_SECURE","false");
        var platform=builder.redirectErrorStream(true).redirectOutput(Path.of("target/local-platform.log").toFile()).start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{platform.destroy();try{platform.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);pg.close();}catch(Exception ignored){}}));
        boolean ready=false;
        for(int i=0;i<300;i++){try{if(login(password)){ready=true;break;}}catch(Exception ignored){}Thread.sleep(100);}
        if(!ready){platform.destroy();pg.close();throw new IllegalStateException("D did not start; see services/rooms/target/local-platform.log");}
        var me=call("GET","/auth/me",null);String department=me.get("departmentId").asText();
        for(var item:List.of(new String[]{"applicant","演示申请人","ROOM_APPLICANT"},new String[]{"reviewer","演示初审员","ROOM_REVIEWER"},new String[]{"approver","演示终审员","ROOM_APPROVER"}))call("POST","/users",Map.of("username",item[0],"displayName",item[1],"departmentId",department,"roles",List.of(item[2]),"password",password));
        var campus=resource("CAMPUS",null,null,"HD","邯郸校区",List.of());var building=resource("BUILDING",campus.get("id").asText(),null,"EIE-A","信息楼 A 座",List.of());
        var hours=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(day->Map.of("day",day,"start","08:00","end","22:00")).toList();
        for(int i=101;i<=104;i++)resource("ROOM",building.get("id").asText(),department,"A"+i,"A"+i+" · 智慧教室",hours);
        var app=new SpringApplication(RoomsApplication.class);
        app.run("--server.address=127.0.0.1","--server.port=8082","--spring.datasource.url="+pg.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--rooms.platform-url=http://127.0.0.1:8080","--rooms.platform-key="+key,"--rooms.cookie-secure=false");
        System.out.println("D + M2 demo ready. Accounts: admin / applicant / reviewer / approver, using your supplied demo password.");
        System.out.println("M1 is not simulated. Without a real M1 endpoint, final approval remains PROCESSING and cancellation remains RELEASING.");
    }
    static boolean login(String password) throws Exception {
        csrf=JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
        var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/auth/login")).header("Cookie","XSRF-TOKEN="+csrf).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username","admin","password",password)))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)return false;
        session=response.headers().allValues("Set-Cookie").stream().filter(c->c.startsWith("CF_SESSION=")).findFirst().orElseThrow().split(";",2)[0].substring(11);
        csrf=call("GET","/auth/csrf",null).get("token").asText();return true;
    }
    static JsonNode resource(String type,String parent,String department,String code,String name,Object hours) throws Exception {
        var data=new LinkedHashMap<String,Object>();data.put("type",type);data.put("parentId",parent);data.put("departmentId",department);data.put("code",code);data.put("name",name);data.put("capacity",40);data.put("features",List.of("投影仪","电源"));data.put("openingHours",hours);data.put("active",true);return call("POST","/resources",data);
    }
    static JsonNode call(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1"+path)).header("Cookie","CF_SESSION="+session+"; XSRF-TOKEN="+csrf).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json");
        var response=HTTP.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()>=300)throw new IllegalStateException("Demo setup failed: "+method+" "+path+" "+response.statusCode());return JSON.readTree(response.body());
    }
}
