package kr.fishingstory;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"app.image.enabled=false", "app.magazine.images.enabled=false"})
@ActiveProfiles("test")
@Import(CatchReportImagesSchemaTests.DatabaseConfiguration.class)
class CatchReportImagesSchemaTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired PostgreSQLContainer postgres;
	long nextId;

	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.catch_report_images");
		nextId = 100;
	}

	@Test void readyImagesRequireAnOrderWithinTheGalleryBounds() {
		insert(jdbc, ++nextId, "READY", 1);
		insert(jdbc, ++nextId, "READY", 10);
		for (Integer order : new Integer[] {null, 0, 11}) {
			long id = ++nextId;
			assertThatThrownBy(() -> insert(jdbc, id, "READY", order))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images", Integer.class)).isEqualTo(2);
	}

	@Test void nonReadyImagesHaveNoDisplayOrderAndInvalidUpdatesKeepTheRow() {
		for (String status : List.of("PENDING", "DELETE_PENDING")) {
			insert(jdbc, ++nextId, status, null);
			long id = ++nextId;
			assertThatThrownBy(() -> insert(jdbc, id, status, 1))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		long readyId = ++nextId;
		insert(jdbc, readyId, "READY", 3);
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE fishingstory.catch_report_images SET display_order=NULL WHERE image_id=?", readyId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("SELECT display_order FROM fishingstory.catch_report_images WHERE image_id=?",
				Integer.class, readyId)).isEqualTo(3);
	}

	@Test void upgradeRejectsHistoricalNullWithoutChangingRowsAndResumesAfterCorrection() {
		// A separate disposable database isolates the V10 -> V11 upgrade from later migrations.
		String database = "image_order_" + java.util.UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE DATABASE " + database);
		var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl()
				.replace("/" + postgres.getDatabaseName(), "/" + database),
				postgres.getUsername(), postgres.getPassword());
		Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory").schemas("fishingstory")
				.target("10").load().migrate();
		var old = new JdbcTemplate(dataSource);
		insert(old, 1, "READY", null);
		var before = old.queryForMap("SELECT * FROM fishingstory.catch_report_images WHERE image_id=1");
		var upgrade = Flyway.configure().dataSource(dataSource).defaultSchema("fishingstory")
				.schemas("fishingstory").target("11").load();
		assertThatThrownBy(upgrade::migrate).isInstanceOf(FlywayException.class)
				.hasStackTraceContaining("READY image rows have no display_order");
		assertThat(old.queryForMap("SELECT * FROM fishingstory.catch_report_images WHERE image_id=1")).isEqualTo(before);
		assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("10");
		old.update("UPDATE fishingstory.catch_report_images SET display_order=1 WHERE image_id=1");
		upgrade.migrate();
		assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("11");
		assertThat(old.queryForObject("SELECT display_order FROM fishingstory.catch_report_images WHERE image_id=1",
				Integer.class)).isEqualTo(1);
	}

	private void insert(JdbcTemplate target, long id, String status, Integer order) {
		target.update("""
				INSERT INTO fishingstory.catch_report_images
				(image_id, report_id, user_id, master_object_key, thumbnail_object_key, status, display_order,
				 created_at, expires_at, updated_at)
				VALUES (?, 101, 102, ?, ?, ?, ?, now(), now() + interval '1 hour', now())
				""", id, "master/" + id, "thumbnail/" + id, status, order);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
