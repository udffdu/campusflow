package io.campusflow.maintenance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class MaintenanceApplication {
    public static void main(String[] args) { SpringApplication.run(MaintenanceApplication.class,args); }
}
