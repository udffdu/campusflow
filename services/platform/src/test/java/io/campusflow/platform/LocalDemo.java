package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.LocalTime;
import java.util.*;
import org.springframework.boot.SpringApplication;

/** Opt-in local fixture only. Never packaged in the production jar. */
public class LocalDemo {
    public static void main(String[] args) throws Exception {
        var postgres=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{postgres.close();}catch(Exception ignored){}}));
        var app=new SpringApplication(PlatformApplication.class);
        app.setDefaultProperties(Map.of("spring.datasource.url",postgres.getJdbcUrl("postgres","postgres"),"spring.datasource.username","postgres","spring.datasource.password","","platform.cookie-secure",false));
        // Explicit command line overrides application.yml; no production profile is altered.
        var context=app.run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--platform.cookie-secure=false");
        var users=context.getBean(IdentityService.class);var admin=users.list(0,1).items().getFirst();var service=context.getBean(ResourceService.class);
        var campus=service.create(new ResourceCreate("CAMPUS",null,"HD","邯郸校区",null,0,List.of(),List.of(),true),admin,"local-demo");
        var building=service.create(new ResourceCreate("BUILDING",campus.id(),"EIE-A","信息楼 A 座",null,0,List.of(),List.of(),true),admin,"local-demo");
        var hours=List.of(new OpeningHour(1,LocalTime.of(8,0),LocalTime.of(22,0)),new OpeningHour(2,LocalTime.of(8,0),LocalTime.of(22,0)),new OpeningHour(3,LocalTime.of(8,0),LocalTime.of(22,0)),new OpeningHour(4,LocalTime.of(8,0),LocalTime.of(22,0)),new OpeningHour(5,LocalTime.of(8,0),LocalTime.of(22,0)));
        for(int number=101;number<=104;number++) {
            var room=service.create(new ResourceCreate("ROOM",building.id(),"A"+number,"A"+number+" · "+(number==104?"研讨教室":"智慧教室"),admin.departmentId(),number==104?24:48,List.of("投影仪",number==104?"小组讨论":"电源插座"),hours,true),admin,"local-demo");
            for(int seat=1;seat<=4;seat++) service.create(new ResourceCreate("SEAT",room.id(),"A"+number+"-S"+seat,"座位 "+seat,admin.departmentId(),1,List.of("电源"),List.of(),true),admin,"local-demo");
        }
        context.getBean(NotificationService.class).receive("D",new NotificationEvent("local-welcome",admin.id(),"欢迎来到 CampusFlow","公共服务已就绪。可以从空间目录开始，维护院系资源并邀请团队成员。这里的数据仅为本地演示样例。","ANNOUNCEMENT","local-welcome"));
    }
}
