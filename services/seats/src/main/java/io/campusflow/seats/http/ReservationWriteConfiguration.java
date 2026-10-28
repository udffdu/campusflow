package io.campusflow.seats.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.campusflow.seats.ReservationStore;
import java.time.*;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="m1.write-enabled",havingValue="true")
public class ReservationWriteConfiguration {
    @Bean ReservationWriteAccess reservationWriteAccess(Environment env,Clock clock){
        if(env.getRequiredProperty("m1.csrf-secret").equals(env.getRequiredProperty("m1.service-key")))
            throw new IllegalArgumentException("CSRF秘密不能复用服务凭据");
        return new ReservationWriteAccess(env.getRequiredProperty("m1.browser-origin"),
                Base64.getDecoder().decode(env.getRequiredProperty("m1.csrf-secret")),
                env.getRequiredProperty("m1.service-key").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                clock,Duration.ofMinutes(15),ReservationWriteAccess.Mode.PERSONAL);
    }
    @Bean RouterFunction<ServerResponse> reservationRoutes(DQueryClient d,ReservationStore store,ReservationWriteAccess access,ObjectMapper json){
        return new ReservationHandler(d,store,access,json).routes();
    }
}
