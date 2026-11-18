package kr.fishingstory.conditions;

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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import java.time.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"FISHING_KMA_SERVICE_KEY=", "FISHING_TIDE_SERVICE_KEY="})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FishingConditionsApiTests.DatabaseConfiguration.class)
class FishingConditionsApiTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	String token;
	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at,role) VALUES (102,'회원','회원',now(),'USER')");
		token = "Bearer " + tokens.issue(102, "USER", java.util.UUID.randomUUID().toString()).value();
	}
	@Test void activeMemberCanReadCatalogAndUnconfiguredSectionsWithoutExternalCalls() throws Exception {
		mvc.perform(get("/api/fishing-conditions/locations").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(5));
		mvc.perform(get("/api/fishing-conditions").header("Authorization", token).param("locationId", "incheon").param("date", today().toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.weather.status").value("NOT_CONFIGURED"))
				.andExpect(jsonPath("$.tides.items").isEmpty()).andExpect(jsonPath("$.sea.basis").value("서해중부앞바다"))
				.andExpect(jsonPath("$.tideLevels.status").value("NOT_CONFIGURED")).andExpect(jsonPath("$.weatherSummary").isEmpty())
				.andExpect(jsonPath("$.almanac.multtaeStyle").value("7물때식"))
				.andExpect(jsonPath("$.almanac.lunarDay").isNumber()).andExpect(jsonPath("$.almanac.sunrise").isString());
		mvc.perform(get("/api/fishing-conditions").header("Authorization", token).param("locationId", "seoul").param("date", today().toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.tideLevels.status").value("NOT_APPLICABLE"))
				.andExpect(jsonPath("$.almanac.multtae").isEmpty()).andExpect(jsonPath("$.almanac.sunset").isString());
	}
	@Test void deniesAnonymousAndInactiveEvenForCachedReferenceData() throws Exception {
		mvc.perform(get("/api/fishing-conditions/locations")).andExpect(status().isUnauthorized());
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=102");
		mvc.perform(get("/api/fishing-conditions/locations").header("Authorization", token)).andExpect(status().isForbidden());
		mvc.perform(get("/api/fishing-conditions").header("Authorization", token).param("locationId", "seoul").param("date", today().toString()))
				.andExpect(status().isForbidden());
	}
	@Test void boundsDatesAndLocationsBeforeCallingSuppliers() throws Exception {
		for (String date : new String[] {today().minusDays(1).toString(), today().plusDays(3).toString(), "invalid"}) {
			mvc.perform(get("/api/fishing-conditions").header("Authorization", token).param("locationId", "incheon").param("date", date))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/fishing-conditions").header("Authorization", token).param("locationId", "unknown").param("date", today().toString()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/fishing-conditions").header("Authorization", token).param("locationId", "seoul").param("date", today().plusDays(2).toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.sea.status").value("NOT_APPLICABLE"));
	}
	private LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
