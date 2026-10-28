package io.campusflow.seats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test database owner only: no Spring app or booking route assembly; absent from production jar. */
public final class DatabaseFixture {
    public static void main(String[] args)throws Exception {
        try(var pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start()){
            var json=new ObjectMapper();var input=new BufferedReader(new InputStreamReader(System.in));String line;
            System.out.println("FIXTURE_READY");System.out.flush();
            while((line=input.readLine())!=null&&!line.equals("STOP")){
                String op=json.readTree(line).path("op").asText();Object value;
                if(op.equals("connection"))value=Map.of("url",pg.getJdbcUrl("postgres","postgres"));
                else if(op.equals("stopDatabase")){pg.close();value=Map.of();}
                else if(op.equals("snapshot")){
                    var jdbc=new JdbcTemplate(pg.getPostgresDatabase());var snapshot=new TreeMap<String,Object>();
                    for(String table:List.of("reservations","reservation_requests","outbox_events"))snapshot.put(table,jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from m1."+table+" t",String.class));
                    value=snapshot;
                }else throw new IllegalArgumentException("unknown database fixture operation");
                System.out.println("FIXTURE_REPLY "+json.writeValueAsString(value));System.out.flush();
            }
        }
    }
}
