package kr.fishingstory.points.api;

import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.points.infrastructure.PointDemoSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"FISHING_KMA_SERVICE_KEY=", "FISHING_TIDE_SERVICE_KEY="})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(PointApiTests.DatabaseConfiguration.class)
class PointApiTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ObjectMapper json;
	@MockitoBean PointDemoSettings demo;
	private String token;
	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.fishing_point_aliases");
		jdbc.update("DELETE FROM fishingstory.fishing_points");
		jdbc.update("DELETE FROM fishingstory.fishing_zones");
		jdbc.update("DELETE FROM fishingstory.fishing_regions");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at,role) VALUES (501,'포인트회원','포인트회원',now(),'USER')");
		token = "Bearer " + tokens.issue(501, "USER", UUID.randomUUID().toString()).value();
		when(demo.enabled()).thenReturn(false);
		jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name) VALUES (1,'부산'),(2,'경기도'),(3,'빈 지역')");
		jdbc.update("INSERT INTO fishingstory.fishing_zones (zone_id,region_id,name) VALUES (11,1,'영도'),(12,2,'호수 권역'),(13,1,'숨김만 있는 권역'),(14,999,'없는 지역 참조')");
		point(101, 11, "가상 해변", "SEA", "PUBLISHED");
		point(102, 11, "가상 해변", "SEA", "PUBLISHED");
		point(103, 12, "가상 해변", "FRESHWATER", "PUBLISHED");
		point(104, 13, "숨김 장소", "SEA", "HIDDEN");
		point(105, 13, "초안 장소", "SEA", "DRAFT");
		point(106, 14, "고아 권역 장소", "SEA", "PUBLISHED");
		point(107, 999, "고아 포인트", "SEA", "PUBLISHED");
		jdbc.update("INSERT INTO fishingstory.fishing_point_aliases (alias_id,point_id,alias) VALUES (201,101,'바다별칭'),(202,101,'바다별칭추가'),(203,102,'100%_! 장소')");
	}
	@Test void returnsOnlyEligibleAreasWithWholeFilteredCountsAndBounds() throws Exception {
		mvc.perform(get("/api/fishing-regions").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.truncated").value(false))
				.andExpect(jsonPath("$.items[0].type").value("REGION")).andExpect(jsonPath("$.items[0].pointCount").value(1))
				.andExpect(jsonPath("$.items[0].bounds.minLongitude").value(129.0));
		mvc.perform(get("/api/fishing-regions/1/zones").header("Authorization", token).param("waterType", "SEA"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].type").value("ZONE")).andExpect(jsonPath("$.items[0].name").value("영도"))
				.andExpect(jsonPath("$.items[0].regionName").value("부산")).andExpect(jsonPath("$.items[0].pointCount").value(2));
		mvc.perform(get("/api/fishing-regions/999/zones").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}
	@Test void separatesSearchTypesAndDoesNotTurnAreasIntoForecastLocations() throws Exception {
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "부산"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.regions[0].name").value("부산"))
				.andExpect(jsonPath("$.zones").isEmpty()).andExpect(jsonPath("$.points").isEmpty());
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "영도"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.zones[0].regionName").value("부산"))
				.andExpect(jsonPath("$.regions").isEmpty()).andExpect(jsonPath("$.points").isEmpty());
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "가상 해변").param("waterType", "SEA"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(2))
				.andExpect(jsonPath("$.points[0].pointId").value(101)).andExpect(jsonPath("$.points[1].pointId").value(102))
				.andExpect(jsonPath("$.points[0].zoneName").value("영도"));
	}
	@Test void aliasSearchDeduplicatesAndTreatsSqlWildcardsLiterally() throws Exception {
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "  바다별칭  "))
				.andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(1))
				.andExpect(jsonPath("$.points[0].pointId").value(101));
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "%_!"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(1))
				.andExpect(jsonPath("$.points[0].pointId").value(102));
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "없는 장소"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.regions").isEmpty())
				.andExpect(jsonPath("$.zones").isEmpty()).andExpect(jsonPath("$.points").isEmpty());
	}
	@Test void preservesDuplicateNamesAcrossPagesAndBindsCursorToFilters() throws Exception {
		String first = mvc.perform(get("/api/fishing-points").header("Authorization", token).param("limit", "1").param("zoneId", "11"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].pointId").value(101))
				.andReturn().getResponse().getContentAsString();
		String cursor = json.readTree(first).path("nextCursor").asString();
		assertThat(cursor).isNotBlank();
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("limit", "1").param("zoneId", "11").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].pointId").value(102)).andExpect(jsonPath("$.nextCursor").isEmpty());
		for (String water : new String[] {"SEA", "FRESHWATER"}) {
			mvc.perform(get("/api/fishing-points").header("Authorization", token).param("zoneId", "11").param("waterType", water).param("cursor", cursor))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("zoneId", "12").param("cursor", cursor))
				.andExpect(status().isBadRequest());
		when(demo.enabled()).thenReturn(true);
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("zoneId", "11").param("cursor", cursor))
				.andExpect(status().isBadRequest());
	}
	@Test void appliesRegionZoneAndWaterFiltersTogetherAndReturnsOnlyPublicDetail() throws Exception {
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("regionId", "1").param("zoneId", "12"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("waterType", "FRESHWATER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].pointId").value(103));
		mvc.perform(get("/api/fishing-points/101").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.point.pointId").value(101))
				.andExpect(jsonPath("$.point.demo").value(false)).andExpect(jsonPath("$.aliases.length()").value(2));
		for (long id : new long[] {104,105,106,107,999}) {
			mvc.perform(get("/api/fishing-points/" + id).header("Authorization", token)).andExpect(status().isNotFound());
		}
		jdbc.update("UPDATE fishingstory.fishing_points SET publication_status='HIDDEN' WHERE point_id=101");
		mvc.perform(get("/api/fishing-points/101").header("Authorization", token)).andExpect(status().isNotFound());
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "바다별칭"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.points").isEmpty());
	}
	@Test void exposesDemoOnlyWhenEnabledAndRejectsInconsistentDemoParents() throws Exception {
		jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name,is_demo) VALUES (4,'[더미] 지역',true)");
		jdbc.update("INSERT INTO fishingstory.fishing_zones (zone_id,region_id,name,is_demo) VALUES (15,4,'[더미] 권역',true),(16,4,'불일치 권역',false)");
		point(108,15,"[더미] 장소","SEA","PUBLISHED");
		jdbc.update("UPDATE fishingstory.fishing_points SET is_demo=true WHERE point_id=108");
		point(109,16,"불일치 장소","SEA","PUBLISHED");
		mvc.perform(get("/api/fishing-points/108").header("Authorization", token)).andExpect(status().isNotFound());
		when(demo.enabled()).thenReturn(true);
		mvc.perform(get("/api/fishing-points/108").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.point.demo").value(true));
		mvc.perform(get("/api/fishing-points/109").header("Authorization", token)).andExpect(status().isNotFound());
	}
	@Test void reportsSearchTruncationSeparatelyFromAreaPointCount() throws Exception {
		for (int i=0;i<22;i++) point(300+i,11,"대량 장소 " + i,"SEA","PUBLISHED");
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "대량 장소"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(20))
				.andExpect(jsonPath("$.pointsTruncated").value(true)).andExpect(jsonPath("$.zonesTruncated").value(false));
		mvc.perform(get("/api/fishing-regions/1/zones").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].pointCount").value(24));
	}
	@Test void exposesAreaAndSearchLimitsWithoutPresentingPartialResultsAsComplete() throws Exception {
		jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name,sort_order) SELECT 1000+n,'묶음 지역 '||n,10 FROM generate_series(1,201) n");
		jdbc.update("INSERT INTO fishingstory.fishing_zones (zone_id,region_id,name) SELECT 5000+n,1000+n,'묶음 권역 '||n FROM generate_series(1,201) n");
		jdbc.update("INSERT INTO fishingstory.fishing_points (point_id,zone_id,name,water_type,longitude,latitude,publication_status) SELECT 9000+n,5000+n,'묶음 장소 '||n,'SEA',129,35,'PUBLISHED' FROM generate_series(1,201) n");
		mvc.perform(get("/api/fishing-regions").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(200))
				.andExpect(jsonPath("$.truncated").value(true)).andExpect(jsonPath("$.items[2].pointCount").value(1));
		mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", "묶음"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.regions.length()").value(20))
				.andExpect(jsonPath("$.zones.length()").value(20)).andExpect(jsonPath("$.points.length()").value(20))
				.andExpect(jsonPath("$.regionsTruncated").value(true)).andExpect(jsonPath("$.zonesTruncated").value(true))
				.andExpect(jsonPath("$.pointsTruncated").value(true)).andExpect(jsonPath("$.regions[0].pointCount").value(1));
	}
	@Test void deniesAnonymousAndCurrentInactiveMemberAcrossEveryEndpoint() throws Exception {
		for (String url : new String[] {"/api/fishing-regions", "/api/fishing-regions/1/zones", "/api/fishing-points", "/api/fishing-points/101", "/api/fishing-points/search?q=가상"}) {
			mvc.perform(get(url)).andExpect(status().isUnauthorized());
		}
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=501");
		for (String url : new String[] {"/api/fishing-regions", "/api/fishing-regions/1/zones", "/api/fishing-points", "/api/fishing-points/101", "/api/fishing-points/search?q=가상"}) {
			mvc.perform(get(url).header("Authorization", token)).andExpect(status().isForbidden());
		}
	}
	@Test void rejectsInvalidInputsWithoutDisclosingSourceDetails() throws Exception {
		for (String q : new String[] {"", " ", "가".repeat(81), "검색" + (char)0 + "어", "검색\n어"}) {
			mvc.perform(get("/api/fishing-points/search").header("Authorization", token).param("q", q))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		for (String cursor : new String[] {"", "malformed", "A".repeat(1001)}) {
			mvc.perform(get("/api/fishing-points").header("Authorization", token).param("cursor", cursor)).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("regionId", "-1")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("waterType", "TIDAL_FLAT")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/fishing-points").header("Authorization", token).param("limit", "51")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/fishing-points/0").header("Authorization", token)).andExpect(status().isBadRequest());
	}
	@Test void keepsExistingConditionsCatalogAndDoesNotCreateCatchReports() throws Exception {
		var before = jdbc.queryForList("SELECT report_id,location_visibility,memo FROM fishingstory.catch_reports ORDER BY report_id");
		mvc.perform(get("/api/fishing-conditions/locations").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5));
		mvc.perform(get("/api/fishing-points").header("Authorization", token)).andExpect(status().isOk());
		assertThat(jdbc.queryForList("SELECT report_id,location_visibility,memo FROM fishingstory.catch_reports ORDER BY report_id")).isEqualTo(before);
	}
	private void point(long id, long zone, String name, String water, String status) {
		jdbc.update("INSERT INTO fishingstory.fishing_points (point_id,zone_id,name,water_type,longitude,latitude,publication_status) VALUES (?,?,?,?,129,35,?)", id,zone,name,water,status);
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
