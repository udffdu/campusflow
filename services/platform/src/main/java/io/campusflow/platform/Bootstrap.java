package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements ApplicationRunner {
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final String password,email;
    Bootstrap(JdbcTemplate db,PasswordEncoder encoder,@Value("${platform.bootstrap-password}") String password,
              @Value("${platform.bootstrap-email:}") String email) { this.db=db;this.encoder=encoder;this.password=password;this.email=email; }
    @Override @Transactional public void run(ApplicationArguments ignored) {
        // Serializes first-start bootstrap across replicas without a pre-existing user row.
        db.queryForList("select code from roles where code='ADMIN' for update");
        if(db.queryForObject("select count(*) from app_users",Integer.class)>0) return;
        if(password.isBlank()) throw new IllegalStateException("Fresh database requires BOOTSTRAP_ADMIN_PASSWORD (12+ characters)");
        IdentityService.checkPassword(password); UUID id=UUID.randomUUID();
        db.update("insert into app_users(id,username,display_name,email,password_hash,department_id) values(?,'admin','平台管理员',nullif(?,''),?,?)",id,email.strip().toLowerCase(Locale.ROOT),encoder.encode(password),UUID.fromString("10000000-0000-0000-0000-000000000001"));
        db.update("insert into user_roles values(?,'ADMIN')",id);
    }
}
