package kr.fishingstory.conditions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
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
		"server.address=127.0.0.1", "FISHING_KMA_SERVICE_KEY=", "FISHING_TIDE_SERVICE_KEY="})
@ActiveProfiles("test")
@Import({FishingConditionsApiTests.DatabaseConfiguration.class, FishingConditionsHttpTests.ValkeyConfiguration.class})
class FishingConditionsHttpTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ObjectMapper json;
	@Value("${local.server.port}") int port;

	@Test void verifiesHealthAuthenticationAndConditionsOverRealLoopbackHttp() throws Exception {
		jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at,role) VALUES (80901,'HTTP회원','http회원',now(),'USER')");
		String credential = tokens.issue(80901, "USER", UUID.randomUUID().toString()).value();
		var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()) {
			var health = get(client, "/actuator/health", null);
			assertThat(health.statusCode()).isEqualTo(200);
			assertThat(json.readTree(health.body()).path("status").asString()).isEqualTo("UP");
			assertThat(get(client, "/api/fishing-conditions/locations", null).statusCode()).isEqualTo(401);
			var locations = get(client, "/api/fishing-conditions/locations", credential);
			assertThat(locations.statusCode()).isEqualTo(200);
			assertThat(locations.headers().firstValue("Cache-Control")).contains("no-store");
			assertThat(json.readTree(locations.body()).path("items").size()).isEqualTo(5);
			var sea = get(client, "/api/fishing-conditions?locationId=incheon&date=" + today, credential);
			assertThat(sea.statusCode()).isEqualTo(200);
			assertThat(json.readTree(sea.body()).path("tides").path("status").asString()).isEqualTo("NOT_CONFIGURED");
			assertThat(get(client, "/api/fishing-conditions?locationId=incheon&date=" + today.plusDays(3), credential).statusCode()).isEqualTo(400);
			assertThat(get(client, "/api/fishing-conditions?locationId=unknown&date=" + today, credential).statusCode()).isEqualTo(400);
			var inland = get(client, "/api/fishing-conditions?locationId=seoul&date=" + today, credential);
			assertThat(inland.statusCode()).isEqualTo(200);
			assertThat(json.readTree(inland.body()).path("sea").path("status").asString()).isEqualTo("NOT_APPLICABLE");
			jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=80901");
			assertThat(get(client, "/api/fishing-conditions/locations", credential).statusCode()).isEqualTo(403);
		}
		System.out.println("Loopback HTTP: health 200, anonymous 401, catalog/sea/inland 200, invalid date/location 400, inactive member 403.");
	}
	private HttpResponse<String> get(HttpClient client, String path, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
		if (token != null) request.header("Authorization", "Bearer " + token);
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class ValkeyConfiguration {
		@Bean @ServiceConnection(name = "redis") GenericContainer<?> valkey() {
			return new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
		}
	}
}
