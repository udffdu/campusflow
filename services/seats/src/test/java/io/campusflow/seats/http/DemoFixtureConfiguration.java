package io.campusflow.seats.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.campusflow.seats.ReservationStore;
import java.time.*;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.*;

/** Explicit primary source only: excluded from component scanning and absent from production jar. */
@TestConfiguration
public class DemoFixtureConfiguration {
    @Bean DemoWritePermit demoWritePermit(Environment env,Clock clock){
        return new DemoWritePermit(env.getRequiredProperty("server.address"),env.getRequiredProperty("M1_DEMO_ORIGIN"),
                Base64.getDecoder().decode(env.getRequiredProperty("M1_DEMO_CSRF_SECRET")),
                env.getRequiredProperty("m1.service-key").getBytes(java.nio.charset.StandardCharsets.UTF_8),clock,Duration.ofMinutes(15));
    }
    @Bean RouterFunction<ServerResponse> demoRoutes(DQueryClient d,ReservationStore store,DemoWritePermit permit,ObjectMapper json){
        return new DemoReservationHandler(d,store,permit,json).routes();
    }
}
