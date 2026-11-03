package io.campusflow.platform;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class PlatformMigrationConfiguration {
    @Bean FlywayMigrationStrategy roomPermissionUpgrade(){
        return PlatformMigrationConfiguration::migrate;
    }

    /** Keep the already-issued V2.1 checksum and allow only this known additive backfill.
     * Other late scripts, missing migrations and checksum mismatches still fail validation.
     * Normal startup keeps Flyway's default ordered migration policy.
     */
    static void migrate(Flyway flyway){
        var ignored=Arrays.stream(flyway.info().all())
                .filter(info->info.getState()==MigrationState.IGNORED).toList();
        if(ignored.size()==1
                && MigrationVersion.fromVersion("2.1").equals(ignored.getFirst().getVersion())
                && "V2_1__room_permissions.sql".equals(ignored.getFirst().getScript())){
            Flyway.configure().configuration(flyway.getConfiguration()).outOfOrder(true).load().migrate();
        }else{
            flyway.migrate();
        }
    }
}
