package io.campusflow.platform;

import static org.assertj.core.api.Assertions.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformMigrationUpgradeTest {
    private static EmbeddedPostgres postgres;
    private static final List<String> M1=List.of("V3__seat_booking_metadata.sql","V4__email_notifications.sql",
            "V5__seat_operations_permission.sql","V6__email_claim_token.sql");
    private static final String ROOMS="V2_1__room_permissions.sql";
    @TempDir Path scripts;
    private String schema;
    @BeforeAll static void start()throws Exception{
        postgres=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start();
    }
    @AfterAll static void stop()throws Exception{if(postgres!=null)postgres.close();}
    @BeforeEach void baseline()throws Exception{
        schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        copy("db/migration/","V1__platform.sql");copy("db/migration/","V2__permission_catalog.sql");
    }
    private void copy(String root,String name)throws Exception{
        try(var resource=getClass().getClassLoader().getResourceAsStream(root+name)){
            assertThat(resource).as(root+name).isNotNull();Files.copy(resource,scripts.resolve(name));
        }
    }
    private void addM1()throws Exception{for(String name:M1)copy("migration-fixtures/m1/",name);}
    private Flyway flyway(){return Flyway.configure().dataSource(postgres.getPostgresDatabase())
            .schemas(schema).defaultSchema(schema).locations("filesystem:"+scripts).load();}
    private List<Map<String,Object>> history(){return new JdbcTemplate(postgres.getPostgresDatabase()).queryForList(
            "select version,checksum from "+schema+".flyway_schema_history where version is not null order by installed_rank");}
    private void assertCompleteAndReplayable(){
        var db=new JdbcTemplate(postgres.getPostgresDatabase());
        assertThat(db.queryForObject("select count(*) from "+schema+".permission_catalog where code like 'rooms.%'",Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("select count(*) from "+schema+".permission_catalog where code like 'seats.%'",Integer.class)).isEqualTo(4);
        assertThat(history()).hasSize(7);
        // Once the known backfill is recorded, ordinary Flyway validation/migration succeeds.
        flyway().validate();assertThat(flyway().migrate().migrationsExecuted).isZero();
        PlatformMigrationConfiguration.migrate(flyway());assertThat(history()).hasSize(7);
    }
    @Test void m1BeforeM2BackfillsOnlyV21AndPreservesAppliedChecksums()throws Exception{
        addM1();flyway().migrate();var before=history();copy("db/migration/",ROOMS);
        assertThatThrownBy(()->flyway().migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("2.1");
        PlatformMigrationConfiguration.migrate(flyway());
        assertThat(history()).containsAll(before);assertCompleteAndReplayable();
    }
    @Test void oldM1V5CanBackfillRoomsAndApplyNewEmailMigrationTogether()throws Exception{
        addM1();
        Flyway.configure().configuration(flyway().getConfiguration()).target("5").load().migrate();
        copy("db/migration/",ROOMS);PlatformMigrationConfiguration.migrate(flyway());assertCompleteAndReplayable();
    }
    @Test void m2BeforeM1KeepsExistingV21AndAppliesHigherVersions()throws Exception{
        copy("db/migration/",ROOMS);flyway().migrate();var before=history();addM1();
        PlatformMigrationConfiguration.migrate(flyway());
        assertThat(history()).containsAll(before);assertCompleteAndReplayable();
    }
    @Test void freshCombinedDatabaseMigratesInOrder()throws Exception{
        addM1();copy("db/migration/",ROOMS);PlatformMigrationConfiguration.migrate(flyway());assertCompleteAndReplayable();
    }
    @Test void unrelatedLateScriptStillFailsWithoutApplyingAnyBackfill()throws Exception{
        addM1();flyway().migrate();var before=history();copy("db/migration/",ROOMS);
        Files.writeString(scripts.resolve("V2_2__unexpected.sql"),"CREATE TABLE unexpected_backfill(id int);\n");
        assertThatThrownBy(()->PlatformMigrationConfiguration.migrate(flyway())).isInstanceOf(FlywayException.class);
        assertThat(history()).isEqualTo(before);
    }
    @Test void changedAppliedMigrationStillFailsChecksumValidation()throws Exception{
        addM1();flyway().migrate();var before=history();copy("db/migration/",ROOMS);
        Files.writeString(scripts.resolve("V3__seat_booking_metadata.sql"),"\nSELECT 1;\n",StandardOpenOption.APPEND);
        assertThatThrownBy(()->PlatformMigrationConfiguration.migrate(flyway()))
                .isInstanceOf(FlywayException.class).hasMessageContaining("checksum");
        assertThat(history()).isEqualTo(before);
    }
    @Test void missingAppliedMigrationStillFailsValidation()throws Exception{
        addM1();flyway().migrate();var before=history();copy("db/migration/",ROOMS);
        Files.delete(scripts.resolve("V4__email_notifications.sql"));
        assertThatThrownBy(()->PlatformMigrationConfiguration.migrate(flyway()))
                .isInstanceOf(FlywayException.class).hasMessageContaining("not resolved locally");
        assertThat(history()).isEqualTo(before);
    }
}
