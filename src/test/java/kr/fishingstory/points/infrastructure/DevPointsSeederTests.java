package kr.fishingstory.points.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(DevPointsSeederTests.DatabaseConfiguration.class)
class DevPointsSeederTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired SnowflakeIdGenerator ids;
	@Autowired PlatformTransactionManager manager;
	@PersistenceContext EntityManager entityManager;
	private DevPointsSeeder seeder;
	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.fishing_point_aliases");
		jdbc.update("DELETE FROM fishingstory.fishing_points");
		jdbc.update("DELETE FROM fishingstory.fishing_zones");
		jdbc.update("DELETE FROM fishingstory.fishing_regions");
		seeder = new DevPointsSeeder(ids,manager);
		ReflectionTestUtils.setField(seeder,"entityManager",entityManager);
	}
	@Test void repeatingTheSeederPreservesIdsAndStatusAndNeverMakesRealData() {
		var args = new DefaultApplicationArguments(new String[0]);
		seeder.run(args);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_regions WHERE is_demo",Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_zones WHERE is_demo",Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points WHERE is_demo",Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points WHERE is_demo AND publication_status='PUBLISHED'",Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points WHERE NOT is_demo",Integer.class)).isZero();
		jdbc.update("UPDATE fishingstory.fishing_points SET publication_status='HIDDEN' WHERE name='[더미] 가상 해변 A'");
		var before = jdbc.queryForList("SELECT point_id,zone_id,name,publication_status FROM fishingstory.fishing_points ORDER BY point_id");
		seeder.run(args);
		assertThat(jdbc.queryForList("SELECT point_id,zone_id,name,publication_status FROM fishingstory.fishing_points ORDER BY point_id")).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_point_aliases",Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT min(point_id) FROM fishingstory.fishing_points",Long.class)).isPositive();
	}
	@Test void conflictingRealRowsAreNotOverwrittenAndPartialDemoChangesRollBack() {
		jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name) VALUES (1,'[더미] 경기도')");
		assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[0])))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Review conflicting demo");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_regions",Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT is_demo FROM fishingstory.fishing_regions WHERE region_id=1",Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points",Integer.class)).isZero();
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
