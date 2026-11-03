package io.campusflow.maintenance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class MaintenanceApplication {
    public static void main(String[] args) { SpringApplication.run(MaintenanceApplication.class,args); }
}
