package kr.fishingstory.user.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(NicknameChangeApiTests.DatabaseConfiguration.class)
class NicknameChangeApiTests {
	static final long USER_ID = 9007199254740993L;
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ObjectMapper json;
	String access;

	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		insertMember(USER_ID, "Fisher", "fisher@example.com");
		insertMember(456, "Other", "other@example.com");
		access = tokens.issue(USER_ID, "USER", UUID.randomUUID().toString()).value();
	}

	@Test void changesNormalizedNicknameAndReturnsNextAvailableTime() throws Exception {
		allowChange();
		mvc.perform(request("  낚시  왕  ").param("userId", "456"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(jsonPath("$.nickname").value("낚시 왕"))
				.andExpect(jsonPath("$.nicknameChangedAt").isString())
				.andExpect(jsonPath("$.nicknameChangeAvailableAt").isString());
		assertThat(jdbc.queryForMap("SELECT nickname, nickname_key, nickname_changed_at = updated_at AS together FROM fishingstory.users WHERE user_id=?", USER_ID))
				.containsEntry("nickname", "낚시 왕").containsEntry("nickname_key", "낚시 왕")
				.containsEntry("together", true);
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users WHERE user_id=456", String.class)).isEqualTo("Other");
	}

	@Test void rejectsChangeBeforeThirtyDaysWithRetryAfter() throws Exception {
		var before = jdbc.queryForObject("SELECT nickname_changed_at FROM fishingstory.users WHERE user_id=?", OffsetDateTime.class, USER_ID);
		var result = mvc.perform(request("Changed"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_CHANGE_TOO_SOON"))
				.andExpect(jsonPath("$.retryAfterSeconds", allOf(greaterThan(29 * 86400), lessThanOrEqualTo(30 * 86400))))
				.andExpect(header().exists("Retry-After")).andReturn();
		assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After"))).isBetween(29L * 86400, 30L * 86400);
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users WHERE user_id=?", String.class, USER_ID)).isEqualTo("Fisher");
		assertThat(jdbc.queryForObject("SELECT nickname_changed_at FROM fishingstory.users WHERE user_id=?", OffsetDateTime.class, USER_ID)).isEqualTo(before);
	}

	@Test void sameDisplayNicknameIsIdempotentDuringCooldown() throws Exception {
		var before = jdbc.queryForObject("SELECT nickname_changed_at FROM fishingstory.users WHERE user_id=?", OffsetDateTime.class, USER_ID);
		mvc.perform(request(" Fisher ")).andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("Fisher"));
		assertThat(jdbc.queryForObject("SELECT nickname_changed_at FROM fishingstory.users WHERE user_id=?", OffsetDateTime.class, USER_ID)).isEqualTo(before);
	}

	@Test void firstDirectSettingStartsCooldownEvenWhenDisplayNicknameIsUnchanged() throws Exception {
		jdbc.update("UPDATE fishingstory.users SET nickname_changed_at=NULL WHERE user_id=?", USER_ID);
		mvc.perform(request(" Fisher ")).andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("Fisher"))
				.andExpect(jsonPath("$.nicknameChangedAt").isString())
				.andExpect(jsonPath("$.nicknameChangeAvailableAt").isString());
		assertThat(jdbc.queryForObject("SELECT nickname_changed_at IS NOT NULL FROM fishingstory.users WHERE user_id=?",
				Boolean.class, USER_ID)).isTrue();
	}

	@Test void rejectsCaseInsensitiveDuplicate() throws Exception {
		allowChange();
		mvc.perform(request("OTHER")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_USED"));
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users WHERE user_id=?", String.class, USER_ID)).isEqualTo("Fisher");
	}

	@ParameterizedTest
	@ValueSource(strings = {"가", "Fisher🐟", "ㅋㅋ", "A\tB", "   "})
	void rejectsInvalidNicknameWithoutChangingDatabase(String nickname) throws Exception {
		allowChange();
		mvc.perform(request(nickname)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users WHERE user_id=?", String.class, USER_ID)).isEqualTo("Fisher");
	}

	@Test void currentDatabaseStateAndAuthenticationAreEnforced() throws Exception {
		allowChange();
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(request("Changed")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mvc.perform(patch("/api/users/me/nickname").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("nickname", "Changed"))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test void concurrentChangesCommitOnlyOneNickname() throws Exception {
		allowChange();
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var futures = List.of("First", "Second").stream().map(nickname -> executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return mvc.perform(request(nickname)).andReturn().getResponse().getStatus();
			})).toList();
			start.countDown();
			var statuses = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users WHERE user_id=?", String.class, USER_ID))
				.isIn("First", "Second");
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String nickname) throws Exception {
		return patch("/api/users/me/nickname").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nickname", nickname)));
	}
	private void allowChange() {
		jdbc.update("UPDATE fishingstory.users SET nickname_changed_at=now() - interval '31 days' WHERE user_id=?", USER_ID);
	}
	private void insertMember(long userId, String nickname, String email) {
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, nickname_changed_at, age_confirmed_at) VALUES (?, ?, lower(?), now(), now())", userId, nickname, nickname);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', ?, 'test-hash', now())", userId + 1000, userId, email);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
