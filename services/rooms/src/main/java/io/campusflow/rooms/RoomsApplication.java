package io.campusflow.rooms;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class RoomsApplication {
    public static void main(String[] args) { SpringApplication.run(RoomsApplication.class,args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
