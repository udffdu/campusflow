package io.campusflow.seats;

import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

/** No local passwords or Basic authentication: browser identities are verified online by D. */
@SpringBootApplication
public class SeatsApplication {
    public static void main(String[] args) { SpringApplication.run(SeatsApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean ReservationPolicy reservationPolicy(@Value("${m1.max-advance}") Duration advance,
            @Value("${m1.max-duration}") Duration duration, Clock clock) {
        return new ReservationPolicy(advance, duration, clock);
    }
    @Bean ReservationStore reservationStore(DataSource source, ReservationPolicy policy, Clock clock) {
        return new ReservationStore(source, policy, clock, Duration.ofSeconds(2));
    }
    @Bean SeatAvailabilityQuery seatQuery(ReservationStore store, ReservationPolicy policy) {
        return new SeatAvailabilityQuery(store, policy);
    }
}
