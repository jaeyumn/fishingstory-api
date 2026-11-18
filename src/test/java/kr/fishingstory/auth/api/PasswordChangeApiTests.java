package kr.fishingstory.auth.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.application.EmailLoginCommand;
import kr.fishingstory.auth.application.EmailLoginService;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(PasswordChangeApiTests.Containers.class)
class PasswordChangeApiTests {
	static final long USER_ID = 9007199254740993L;
	static final String EMAIL = "fisher@example.com";
	static final String CURRENT = "Password1";
	static final String NEXT = "NewPassword2";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired PasswordEncoder passwords;
	@Autowired EmailLoginService login;
	@MockitoSpyBean RefreshSessionStore sessions;

	@BeforeEach
	void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, nickname_changed_at, age_confirmed_at) VALUES (?, 'Fisher', 'fisher', now(), now())", USER_ID);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', ?, ?, now())",
				USER_ID + 1, USER_ID, EMAIL, passwords.encode(CURRENT));
	}

	@Test
	void changesHashEndsEveryRefreshSessionAndOnlyNewPasswordCanLogin() throws Exception {
		var current = login(CURRENT, "192.0.2.1");
		var other = login(CURRENT, "192.0.2.2");
		mvc.perform(request(current.accessToken(), CURRENT, NEXT, NEXT))
				.andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().string(""));

		String hash = passwordHash();
		assertThat(passwords.matches(NEXT, hash)).isTrue();
		assertThat(passwords.matches(CURRENT, hash)).isFalse();
		assertThat(sessions.findValid(current.refreshToken())).isEmpty();
		assertThat(sessions.findValid(other.refreshToken())).isEmpty();
		assertThatThrownBy(() -> login(CURRENT, "192.0.2.3")).isInstanceOf(ApiException.class).hasMessage("LOGIN_FAILED");
		assertThat(login(NEXT, "192.0.2.4").refreshToken()).isNotBlank();
	}

	@Test
	void wrongCurrentAndUnchangedPasswordAreRejectedWithoutEndingSessions() throws Exception {
		var tokens = login(CURRENT, "192.0.2.1");
		mvc.perform(request(tokens.accessToken(), "Wrong123", NEXT, NEXT))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));
		mvc.perform(request(tokens.accessToken(), CURRENT, CURRENT, CURRENT))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();
	}

	@Test
	void validatesNewPasswordAndConfirmationBeforeChangingAnything() throws Exception {
		var tokens = login(CURRENT, "192.0.2.1");
		for (var values : List.of(List.of("short1", "short1"), List.of(NEXT, "Mismatch2"))) {
			mvc.perform(request(tokens.accessToken(), CURRENT, values.get(0), values.get(1)))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();
	}

	@Test
	void limitsFiveAttemptsPerMemberBeforeCheckingTheCurrentPassword() throws Exception {
		var tokens = login(CURRENT, "192.0.2.1");
		for (int attempt = 0; attempt < 3; attempt++) {
			mvc.perform(request(tokens.accessToken(), CURRENT, "short1", "short1"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		for (int attempt = 0; attempt < 5; attempt++) {
			mvc.perform(request(tokens.accessToken(), "Wrong123", NEXT, NEXT))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));
		}
		// A full window rejects even the correct password without verifying it.
		mvc.perform(request(tokens.accessToken(), CURRENT, NEXT, NEXT))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_RATE_LIMITED"))
				.andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
				.andExpect(header().string("Cache-Control", "no-store"));
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();

		long otherId = USER_ID + 10;
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, nickname_changed_at, age_confirmed_at) VALUES (?, 'Other', 'other', now(), now())", otherId);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', 'other@example.com', ?, now())",
				otherId + 1, otherId, passwords.encode(CURRENT));
		var other = login.login(new EmailLoginCommand("other@example.com", CURRENT), "192.0.2.2");
		mvc.perform(request(other.accessToken(), CURRENT, NEXT, NEXT)).andExpect(status().isNoContent());

		// Once the member's window clears, the original member can change the password again.
		redis.delete("auth:password-change:user:" + USER_ID);
		mvc.perform(request(tokens.accessToken(), CURRENT, NEXT, NEXT)).andExpect(status().isNoContent());
		assertThat(passwords.matches(NEXT, passwordHash())).isTrue();
	}

	@Test
	void authenticationAndCurrentAccountStateAreEnforced() throws Exception {
		mvc.perform(patch("/api/users/me/password").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body(CURRENT, NEXT, NEXT))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		var tokens = login(CURRENT, "192.0.2.1");
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(request(tokens.accessToken(), CURRENT, NEXT, NEXT))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
	}

	@Test
	void sessionStoreFailureRollsBackPasswordAndPreservesExistingSession() throws Exception {
		var tokens = login(CURRENT, "192.0.2.1");
		doThrow(new DataAccessResourceFailureException("private storage detail")).when(sessions).logoutAll(anyLong());
		mvc.perform(request(tokens.accessToken(), CURRENT, NEXT, NEXT))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private storage detail"))));
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();
	}

	@Test
	void concurrentOldPasswordLoginCannotLeaveARefreshSessionAfterChange() throws Exception {
		var current = login(CURRENT, "192.0.2.1");
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var change = executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return mvc.perform(request(current.accessToken(), CURRENT, NEXT, NEXT)).andReturn().getResponse().getStatus();
			});
			var oldLogin = executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				try { login(CURRENT, "192.0.2.2"); return true; }
				catch (ApiException rejected) { assertThat(rejected).hasMessage("LOGIN_FAILED"); return false; }
			});
			start.countDown();
			assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo(204);
			oldLogin.get(30, TimeUnit.SECONDS);
		}
		assertThat(redis.keys("auth:refresh:*")).isEmpty();
		assertThat(passwords.matches(NEXT, passwordHash())).isTrue();
	}

	private EmailLoginService.Tokens login(String password, String ip) {
		return login.login(new EmailLoginCommand(EMAIL, password), ip);
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
			String accessToken, String current, String next, String confirmation) throws Exception {
		return patch("/api/users/me/password").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body(current, next, confirmation)));
	}
	private Map<String, String> body(String current, String next, String confirmation) {
		return Map.of("currentPassword", current, "newPassword", next, "newPasswordConfirmation", confirmation);
	}
	private String passwordHash() {
		return jdbc.queryForObject("SELECT password_hash FROM fishingstory.user_auth WHERE user_id=?", String.class, USER_ID);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Containers {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
		@Bean @ServiceConnection(name = "redis")
		GenericContainer<?> valkey() { return new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379); }
	}
}
