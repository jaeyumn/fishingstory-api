package kr.fishingstory;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeciesCatalogMigrationTests {
    @Test void upgradesV11WithoutChangingHistoricalNamesDatesLengthsOrSpecies() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory").schemas("fishingstory")
                    .target("11").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("INSERT INTO fishingstory.catch_reports (report_id,user_id,catch_status,water_type,fishing_date) VALUES (800,801,'CAUGHT','SEA','2026-09-20')");
            jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id,report_id,species_id,max_length_cm,sort_order) VALUES (802,800,1003,30.5,0)");
            jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id,report_id,custom_name,sort_order) VALUES (803,800,'옛 직접 입력',1)");
            jdbc.update("INSERT INTO fishingstory.user_fish_collection (collection_id,user_id,species_id,max_length_cm,first_fishing_date,last_fishing_date) VALUES (804,801,1003,30.5,'2026-09-20','2026-09-20')");
            var source = jdbc.queryForList("SELECT report_species_id,report_id,species_id,custom_name,max_length_cm,sort_order FROM fishingstory.catch_report_species ORDER BY sort_order");
            var originalSpecies = jdbc.queryForList("SELECT species_id,name,scientific_name FROM fishingstory.fish_species ORDER BY species_id");
            Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory").schemas("fishingstory").load().migrate();
            assertThat(jdbc.queryForList("SELECT report_species_id,report_id,species_id,custom_name,max_length_cm,sort_order FROM fishingstory.catch_report_species ORDER BY sort_order")).isEqualTo(source);
            assertThat(jdbc.queryForList("SELECT species_id,name,scientific_name FROM fishingstory.fish_species ORDER BY species_id")).isEqualTo(originalSpecies);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_species WHERE max_weight_g IS NULL", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fish_species_regulation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE max_weight_g IS NULL AND max_length_cm=30.5 AND first_fishing_date='2026-09-20' AND last_fishing_date='2026-09-20'", Integer.class)).isEqualTo(1);
        }
    }

    @Test void v13RefusesWeightsAboveTwoTonnesWithoutChangingRowsAndResumesAfterReview() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory").schemas("fishingstory")
                    .target("12").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id,report_id,species_id,max_weight_g,sort_order) VALUES (902,900,1003,2000000.1,0)");
            jdbc.update("INSERT INTO fishingstory.user_fish_collection (collection_id,user_id,species_id,max_weight_g,first_fishing_date,last_fishing_date) VALUES (904,901,1003,2000000.0,'2026-09-20','2026-09-20')");
            var before = jdbc.queryForList("SELECT report_species_id,max_weight_g FROM fishingstory.catch_report_species");
            var upgrade = Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory").schemas("fishingstory")
                    .target("13").load();
            assertThatThrownBy(upgrade::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("Weights above 2,000,000 g exist");
            assertThat(jdbc.queryForList("SELECT report_species_id,max_weight_g FROM fishingstory.catch_report_species")).isEqualTo(before);
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("12");
            jdbc.update("UPDATE fishingstory.catch_report_species SET max_weight_g=2000000.0 WHERE report_species_id=902");
            upgrade.migrate();
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("13");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_species WHERE max_weight_g=2000000.0", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE max_weight_g=2000000.0", Integer.class)).isEqualTo(1);
        }
    }

    @Test void everyMigrationKeepsCultivarsOnAnExistingReviewedOriginal() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            // A missing original and an unreviewed (entry_kind NULL) one both roll the inserting migration back.
            for (String scenario : java.util.List.of("orphan", "unreviewed")) {
                var flyway = withTestMigrations(postgres, admin, scenario);
                var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
                assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
                        .hasStackTraceContaining("Cultivar 9001 needs an existing SPECIES original");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fish_species WHERE species_id=9001", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version='100' AND success", Integer.class)).isZero();
            }
            // A reviewed original registers its cultivar; a later migration may not deactivate that original.
            var flyway = withTestMigrations(postgres, admin, "valid");
            var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("Cultivar 9001 needs an existing SPECIES original");
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("100");
            assertThat(jdbc.queryForObject("SELECT parent_species_id FROM fishingstory.fish_species WHERE species_id=9001", Long.class)).isEqualTo(1003L);
            assertThat(jdbc.queryForObject("SELECT active FROM fishingstory.fish_species WHERE species_id=1003", Boolean.class)).isTrue();
        }
    }

    // Each scenario gets its own database with the real migrations plus test-only V100+ data migrations.
    private static Flyway withTestMigrations(PostgreSQLContainer postgres, JdbcTemplate admin, String scenario) {
        String database = "cultivar_" + scenario;
        admin.execute("CREATE DATABASE " + database);
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database),
                postgres.getUsername(), postgres.getPassword());
        return Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory").schemas("fishingstory")
                .locations("classpath:db/migration", "classpath:db/cultivar-check/" + scenario).load();
    }
}
