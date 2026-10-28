package io.campusflow.seats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-classpath only. Local stdin fixture protocol is never exposed over HTTP or packaged in the jar. */
public final class HttpFixture {
    public static void main(String[] args) throws Exception {
        try(var pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start()) {
            var app=new SpringApplication(SeatsApplication.class);
            try(var context=app.run("--spring.datasource.url="+pg.getJdbcUrl("postgres","postgres"),
                    "--spring.datasource.username=postgres","--spring.datasource.password=",
                    "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
                System.out.println("FIXTURE_READY");System.out.flush();
                var json=new ObjectMapper();var input=new BufferedReader(new InputStreamReader(System.in));String line;
                while((line=input.readLine())!=null && !line.equals("STOP")) {
                    var command=json.readTree(line);String operation=command.path("op").asText();
                    if(operation.equals("create")) {
                        context.getBean(ReservationStore.class).create(new CreateReservation(
                            UUID.fromString(command.path("userId").asText()),UUID.fromString(command.path("roomId").asText()),
                            UUID.fromString(command.path("seatId").asText()),new TimeRange(Instant.parse(command.path("startAt").asText()),
                            Instant.parse(command.path("endAt").asText())),1),command.path("key").asText());
                    }
                    if(operation.equals("stopDatabase")) {pg.close();System.out.println("FIXTURE_REPLY {}");System.out.flush();continue;}
                    if(!Set.of("create","snapshot").contains(operation))throw new IllegalArgumentException("unknown fixture operation");
                    var jdbc=new JdbcTemplate(pg.getPostgresDatabase());var snapshot=new TreeMap<String,Object>();
                    for(String table:List.of("reservations","reservation_requests","outbox_events"))
                        snapshot.put(table,jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from m1."+table+" t",String.class));
                    System.out.println("FIXTURE_REPLY "+json.writeValueAsString(snapshot));System.out.flush();
                }
            }
        }
    }
}
