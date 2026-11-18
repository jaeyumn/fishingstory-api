package kr.fishingstory;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(FishingstoryApiApplicationTests.DatabaseConfiguration.class)
class FishingstoryApiApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Test
	void appliesMigrationAndResolvesApplicationSchema() {
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '14' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '9' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT indexdef FROM pg_indexes WHERE schemaname='fishingstory'
				AND indexname='uq_catch_reports_request'
				""", String.class)).contains("UNIQUE", "user_id, request_key", "request_key IS NOT NULL");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '8' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '7' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT indexdef FROM pg_indexes WHERE schemaname='fishingstory'
				AND indexname='idx_catch_reports_public_recent'
				""", String.class)).contains("fishing_date DESC, created_at DESC, report_id DESC", "PUBLIC");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '6' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '5' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
				.isEqualTo("fishingstory");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '1' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '2' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '3' AND success",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version = '4' AND success",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void supportsGeographyCoordinatesAndDistanceInMeters() {
		Double distance = jdbc.queryForObject("""
				SELECT ST_Distance(
					ST_SetSRID(ST_MakePoint(129.0, 35.0), 4326)::geography,
					ST_SetSRID(ST_MakePoint(129.0, 35.001), 4326)::geography
				)
				""", Double.class);
		assertThat(distance).isBetween(100.0, 120.0);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5")
					.asCompatibleSubstituteFor("postgres"));
		}
	}

}
