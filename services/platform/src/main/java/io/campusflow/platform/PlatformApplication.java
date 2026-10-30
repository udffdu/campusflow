package io.campusflow.platform;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class, args); }
    @Bean Clock clock(){return Clock.systemUTC();}
}
