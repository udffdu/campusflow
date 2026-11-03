package io.campusflow.platform;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.boot.SpringApplication;

/** Isolated test/demo process: no seed SQL or production-jar entry point. Seed through public D APIs. */
public final class HttpFixture {
    public static void main(String[] args) throws Exception {
        try(var pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start()) {
            var app=new SpringApplication(PlatformApplication.class);
            try(var context=app.run("--spring.datasource.url="+pg.getJdbcUrl("postgres","postgres"),
                    "--spring.datasource.username=postgres","--spring.datasource.password=","--platform.cookie-secure=false",
                    "--server.address=127.0.0.1","--logging.level.root=WARN","--spring.main.banner-mode=off")) {
                System.out.println("FIXTURE_READY");System.out.flush();
                new BufferedReader(new InputStreamReader(System.in)).readLine();
            }
        }
    }
}
