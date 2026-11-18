package kr.fishingstory.user.api;

import java.util.UUID;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CurrentUserApiTests.DatabaseConfiguration.class)
class CurrentUserApiTests {
	static final long USER_ID = 9007199254740993L;
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@MockitoSpyBean UserQueryMapper users;
	String access;

	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		insertMember(USER_ID, 101, "Fisher", "fisher@example.com");
		insertMember(456, 102, "Other", "other@example.com");
		access = tokens.issue(USER_ID, "USER", UUID.randomUUID().toString()).value();
	}

	@Test void returnsOnlyOwnFiveFieldsAndIgnoresRequestedUserId() throws Exception {
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access).param("userId", "456"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$.userId").value(org.hamcrest.Matchers.is(USER_ID), Long.class))
				.andExpect(jsonPath("$.nickname").value("Fisher"))
				.andExpect(jsonPath("$.provider").value("EMAIL"))
				.andExpect(jsonPath("$.email").value("fisher@example.com"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.providerUserId").doesNotExist());
		verify(users).findCurrentEmailUser(USER_ID);
		verify(users, never()).findCurrentEmailUser(456);
		assertThat(users.findCurrentEmailUser(USER_ID).orElseThrow().toString()).doesNotContain("Fisher", "fisher@example.com");
	}

	@ParameterizedTest @ValueSource(strings = {"pending", "deleted", "missing-auth", "social"})
	void currentDatabaseStateOverridesPreviouslyIssuedAccess(String scenario) throws Exception {
		switch (scenario) {
			case "pending" -> jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
			case "deleted" -> jdbc.update("DELETE FROM fishingstory.users WHERE user_id=?", USER_ID);
			case "missing-auth" -> jdbc.update("DELETE FROM fishingstory.user_auth WHERE user_id=?", USER_ID);
			case "social" -> jdbc.update("UPDATE fishingstory.user_auth SET provider='KAKAO', provider_user_id='social-id', password_hash=NULL, email_verified_at=NULL WHERE user_id=?", USER_ID);
		}
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.nickname").doesNotExist());
	}

	@Test void nextRequestReadsCurrentNicknameWithSameAccessToken() throws Exception {
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("Fisher"));
		jdbc.update("UPDATE fishingstory.users SET nickname='New Name', nickname_key='new name', role='ADMIN' WHERE user_id=?", USER_ID);
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("New Name"))
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test void missingAndInvalidAccessNeverReachQuery() throws Exception {
		mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer invalid-token"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		verifyNoInteractions(users);
	}

	@Test void databaseFailureDoesNotExposePersonalInformation() throws Exception {
		doThrow(new DataAccessResourceFailureException("private database detail fisher@example.com")).when(users).findCurrentEmailUser(USER_ID);
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fisher@example.com"))))
				.andExpect(jsonPath("$.userId").doesNotExist());
	}

	private void insertMember(long userId, long authId, String nickname, String email) {
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at) VALUES (?, ?, lower(?), now())", userId, nickname, nickname);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', ?, 'test-hash', now())", authId, userId, email);
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
