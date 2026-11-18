package kr.fishingstory.points;

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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FishingPointsSchemaTests.DatabaseConfiguration.class)
class FishingPointsSchemaTests {
	@Autowired JdbcTemplate jdbc;
	@BeforeEach void clear() {
		jdbc.update("DELETE FROM fishingstory.fishing_point_aliases");
		jdbc.update("DELETE FROM fishingstory.fishing_points");
		jdbc.update("DELETE FROM fishingstory.fishing_zones");
		jdbc.update("DELETE FROM fishingstory.fishing_regions");
	}
	@Test void generatesGeographyFromCheckedCoordinatesAndDefaultsToDraft() {
		insert(1,"동명 장소",129.08,35.06);
		insert(2,"동명 장소",129.09,35.07);
		var row = jdbc.queryForMap("SELECT publication_status,is_demo,ST_SRID(coordinate::geometry) AS srid,ST_X(coordinate::geometry) AS x,ST_Y(coordinate::geometry) AS y FROM fishingstory.fishing_points WHERE point_id=1");
		assertThat(row).containsEntry("publication_status","DRAFT").containsEntry("is_demo",false)
				.containsEntry("srid",4326).containsEntry("x",129.08).containsEntry("y",35.06);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points",Integer.class)).isEqualTo(2);
	}
	@Test void rejectsOutOfRangeAndNonFiniteCoordinatesBeforeTheyCanBeNormalized() {
		for (double longitude : new double[] {-180.1,180.1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
			assertThatThrownBy(() -> insert(1,"장소",longitude,35)).isInstanceOf(DataIntegrityViolationException.class);
		}
		for (double latitude : new double[] {-90.1,90.1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
			assertThatThrownBy(() -> insert(1,"장소",129,latitude)).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points",Integer.class)).isZero();
	}
	@Test void constrainsCodesNamesAndAliasesWithoutGloballyReservingAnAlias() {
		insert(1,"정상 장소",129,35); insert(2,"다른 장소",129,35);
		for (String name : new String[] {"", " 공백", "공백 ", "줄\n바꿈", "가".repeat(121)}) {
			assertThatThrownBy(() -> insert(3,name,129,35)).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.fishing_points SET water_type='UNKNOWN' WHERE point_id=1")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.fishing_points SET publication_status='VISIBLE' WHERE point_id=1")).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("INSERT INTO fishingstory.fishing_point_aliases (alias_id,point_id,alias) VALUES (1,1,'공통 별칭'),(2,2,'공통 별칭')");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO fishingstory.fishing_point_aliases (alias_id,point_id,alias) VALUES (3,1,'공통 별칭')")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name) VALUES (0,'지역')")).isInstanceOf(DataIntegrityViolationException.class);
	}
	private void insert(long id,String name,double longitude,double latitude) {
		jdbc.update("INSERT INTO fishingstory.fishing_points (point_id,zone_id,name,water_type,longitude,latitude) VALUES (?,11,?,'SEA',?,?)",id,name,longitude,latitude);
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
