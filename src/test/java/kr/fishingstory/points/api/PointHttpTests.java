package kr.fishingstory.points.api;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"server.address=127.0.0.1", "FISHING_KMA_SERVICE_KEY=", "FISHING_TIDE_SERVICE_KEY=",
		"app.dev.seed-points=false", "app.image.enabled=false", "app.magazine.images.enabled=false"})
@ActiveProfiles("test")
@Import({PointApiTests.DatabaseConfiguration.class, PointHttpTests.ValkeyConfiguration.class})
class PointHttpTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ObjectMapper json;
	@Value("${local.server.port}") int port;
	private int requests;

	@Test void verifiesPublishedRegistryAndBoundariesOverRealLoopbackHttp() throws Exception {
		jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at) VALUES (82901,'PointHTTP','pointhttp',now())");
		jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name) VALUES (1,'테스트 부산'),(2,'테스트 경기')");
		jdbc.update("INSERT INTO fishingstory.fishing_zones (zone_id,region_id,name) VALUES (11,1,'테스트 영도'),(12,2,'테스트 호수')");
		jdbc.update("""
				INSERT INTO fishingstory.fishing_points (point_id,zone_id,name,water_type,longitude,latitude,publication_status)
				VALUES (101,11,'같은 장소','SEA',129,35,'PUBLISHED'),(102,11,'같은 장소','SEA',129.1,35.1,'PUBLISHED'),
				(103,12,'같은 장소','FRESHWATER',127,37,'PUBLISHED'),(104,11,'숨김 장소','SEA',129,35,'HIDDEN'),
				(105,11,'초안 장소','SEA',129,35,'DRAFT')
				""");
		jdbc.update("INSERT INTO fishingstory.fishing_point_aliases (alias_id,point_id,alias) VALUES (1,101,'해변 별칭'),(2,101,'해변 별칭 추가'),(3,102,'100%_! 장소')");
		jdbc.update("INSERT INTO fishingstory.fishing_regions (region_id,name,is_demo) VALUES (3,'[더미] 지역',true)");
		jdbc.update("INSERT INTO fishingstory.fishing_zones (zone_id,region_id,name,is_demo) VALUES (13,3,'[더미] 권역',true)");
		jdbc.update("INSERT INTO fishingstory.fishing_points (point_id,zone_id,name,water_type,longitude,latitude,publication_status,is_demo) VALUES (106,13,'[더미] 장소','SEA',129,35,'PUBLISHED',true)");
		String token = tokens.issue(82901,"USER",UUID.randomUUID().toString()).value();
		var reportsBefore = jdbc.queryForList("SELECT report_id,location_visibility,memo FROM fishingstory.catch_reports ORDER BY report_id");
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
				.followRedirects(HttpClient.Redirect.NEVER).build()) {
			assertThat(json.readTree(get(client,"/actuator/health",null,200).body()).path("status").asString()).isEqualTo("UP");
			get(client,"/api/fishing-points",null,401);
			var regions = get(client,"/api/fishing-regions?waterType=SEA",token,200);
			assertThat(regions.headers().firstValue("Cache-Control")).contains("no-store");
			assertThat(json.readTree(regions.body()).path("items").size()).isEqualTo(1);
			assertThat(json.readTree(regions.body()).path("items").get(0).path("pointCount").asInt()).isEqualTo(2);
			var zones = json.readTree(get(client,"/api/fishing-regions/1/zones",token,200).body());
			assertThat(zones.path("items").get(0).path("name").asString()).isEqualTo("테스트 영도");
			var regionSearch = json.readTree(get(client,search("부산"),token,200).body());
			assertThat(regionSearch.path("regions").size()).isEqualTo(1);
			assertThat(regionSearch.path("points").isEmpty()).isTrue();
			var zoneSearch = json.readTree(get(client,search("영도"),token,200).body());
			assertThat(zoneSearch.path("zones").size()).isEqualTo(1);
			assertThat(zoneSearch.path("points").isEmpty()).isTrue();
			var alias = json.readTree(get(client,search("해변 별칭"),token,200).body());
			assertThat(alias.path("points").size()).isEqualTo(1);
			assertThat(alias.path("points").get(0).path("pointId").asLong()).isEqualTo(101);
			var literal = json.readTree(get(client,search("%_!"),token,200).body());
			assertThat(literal.path("points").size()).isEqualTo(1);
			assertThat(literal.path("points").get(0).path("pointId").asLong()).isEqualTo(102);
			var first = json.readTree(get(client,"/api/fishing-points?zoneId=11&limit=1",token,200).body());
			assertThat(first.path("items").get(0).path("pointId").asLong()).isEqualTo(101);
			String cursor = first.path("nextCursor").asString();
			assertThat(cursor).isNotBlank();
			var second = json.readTree(get(client,"/api/fishing-points?zoneId=11&limit=1&cursor="+cursor,token,200).body());
			assertThat(second.path("items").get(0).path("pointId").asLong()).isEqualTo(102);
			assertThat(second.path("nextCursor").isNull()).isTrue();
			get(client,"/api/fishing-points?zoneId=12&cursor="+cursor,token,400);
			var inland = json.readTree(get(client,"/api/fishing-points?waterType=FRESHWATER",token,200).body());
			assertThat(inland.path("items").size()).isEqualTo(1);
			assertThat(inland.path("items").get(0).path("pointId").asLong()).isEqualTo(103);
			var detail = json.readTree(get(client,"/api/fishing-points/101",token,200).body());
			assertThat(detail.path("point").path("demo").asBoolean()).isFalse();
			assertThat(detail.path("aliases").size()).isEqualTo(2);
			for (long id : new long[] {104,105,106,999}) get(client,"/api/fishing-points/"+id,token,404);
			get(client,search(" "),token,400);
			get(client,"/api/fishing-points?limit=51",token,400);
			var legacy = json.readTree(get(client,"/api/fishing-conditions/locations",token,200).body());
			assertThat(legacy.path("items").size()).isEqualTo(5);
			jdbc.update("UPDATE fishingstory.fishing_points SET publication_status='HIDDEN' WHERE point_id=101");
			get(client,"/api/fishing-points/101",token,404);
			assertThat(json.readTree(get(client,search("해변 별칭"),token,200).body()).path("points").isEmpty()).isTrue();
			jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=82901");
			get(client,"/api/fishing-points",token,403);
			get(client,"/api/fishing-points/102",token,403);
		}
		assertThat(jdbc.queryForList("SELECT report_id,location_visibility,memo FROM fishingstory.catch_reports ORDER BY report_id")).isEqualTo(reportsBefore);
		System.out.println("Points loopback HTTP: "+requests+" requests passed (health, auth/ACTIVE, regions/zones, search/literal aliases, cursors/filters, published/detail, demo exclusion, legacy conditions and report preservation).");
	}
	private String search(String query) { return "/api/fishing-points/search?q="+URLEncoder.encode(query,StandardCharsets.UTF_8); }
	private HttpResponse<String> get(HttpClient client,String path,String token,int expected) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).GET();
		if (token != null) request.header("Authorization","Bearer "+token);
		var response = client.send(request.build(),HttpResponse.BodyHandlers.ofString());
		requests++;
		assertThat(response.statusCode()).as("HTTP request %s",requests).isEqualTo(expected);
		return response;
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class ValkeyConfiguration {
		@Bean @ServiceConnection(name="redis") GenericContainer<?> valkey() {
			return new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
		}
	}
}
