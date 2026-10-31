package io.campusflow.seats.http;

import io.campusflow.seats.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="m1.lifecycle.enabled",havingValue="true")
class ReservationLifecycleConfiguration {
    @Bean ReservationLifecycleStore reservationLifecycleStore(DataSource source,ReservationStore reservations,Clock clock){
        return new ReservationLifecycleStore(source,reservations,clock);
    }
    @Bean ReservationLifecycleWorker reservationLifecycleWorker(ReservationLifecycleStore store,
            @Value("${m1.lifecycle.scan-limit}") int limit){
        return new ReservationLifecycleWorker(store,limit);
    }
}
