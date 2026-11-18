package kr.fishingstory.auth.application;

import java.util.Map;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.auth.infrastructure.valkey.SignupEmailCodeStore;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(EmailLoginIntegrationTests.Containers.class)
class EmailLoginIntegrationTests {
	static final String EMAIL = "fisher@example.com";
	static final String PASSWORD = " Password1 ";
	static final String BODY = "{\"email\":\" Fisher@Example.COM \",\"password\":\" Password1 \"}";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired JwtDecoder decoder;
	@Autowired EmailSignupService signup;
	@Autowired EmailLoginService login;
	@Autowired EmailSendQuota quota;
	@Autowired SignupEmailCodeStore codes;
	@Autowired SignupVerificationStore verification;
	@MockitoSpyBean RefreshSessionStore sessions;
	@MockitoSpyBean AccessTokenIssuer accessTokens;
	long userId;

	@BeforeEach
	void prepareMember() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("DELETE FROM fishingstory.terms");
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		jdbc.update("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, requires_consent, is_required, effective_at, published_at)
				VALUES (12, 'TERMS_OF_SERVICE', 'test', 'Test', 'Test content', true, true, now(), now())
				""");
		String token = "A".repeat(43);
		codes.replace(EMAIL, quota.reserve(EMAIL, "192.0.2.1").reservation(), "001234");
		verification.verify(EMAIL, "001234", token);
		userId = signup.signup(new EmailSignupCommand(EMAIL, token, PASSWORD, PASSWORD, "Fisher", true, 12L)).userId();
	}

	@Test
	void signupThenLoginReturnsVerifiableJwtAndHashOnlyRefreshSession() throws Exception {
		var response = mvc.perform(post("/api/auth/login/email").contentType("application/json").content(BODY))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().doesNotExist("Set-Cookie")).andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.password").doesNotExist()).andReturn().getResponse();
		var tokens = mapper.readValue(response.getContentAsString(), EmailLoginService.Tokens.class);
		var jwt = decoder.decode(tokens.accessToken());
		assertThat(jwt.getSubject()).isEqualTo(Long.toString(userId));
		assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
		assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
		assertThat(jwt.getExpiresAt()).isEqualTo(tokens.accessTokenExpiresAt());
		assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
		assertThat(java.util.Base64.getUrlDecoder().decode(tokens.refreshToken())).hasSize(32);
		var keys = redis.keys("auth:refresh:*");
		assertThat(keys).hasSize(1);
		String key = keys.iterator().next();
		assertThat(key).doesNotContain(tokens.refreshToken(), EMAIL);
		Map<Object, Object> stored = redis.opsForHash().entries(key);
		assertThat(stored).containsEntry("userId", Long.toString(userId)).containsEntry("sessionId", jwt.getClaimAsString("sid"));
		assertThat(stored.toString()).doesNotContain(tokens.refreshToken(), tokens.accessToken(), PASSWORD, EMAIL);
		assertThat(redis.getExpire(key)).isBetween(2591990L, 2592000L);
		assertThat(Long.parseLong((String) stored.get("expiresAt"))).isEqualTo(tokens.refreshTokenExpiresAt().toEpochMilli());
		mvc.perform(get("/login-test/private").header("Authorization", "Bearer " + tokens.accessToken()))
				.andExpect(status().isOk()).andExpect(content().string(userId + ":ROLE_USER"));
		assertThat(tokens.toString()).doesNotContain(tokens.accessToken(), tokens.refreshToken());
	}

	@Test
	void multipleLoginsCreateIndependentSessionsAndJwtIds() {
		var first = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		var second = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.2");
		assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
		assertThat(decoder.decode(first.accessToken()).getId()).isNotEqualTo(decoder.decode(second.accessToken()).getId());
		assertThat(decoder.decode(first.accessToken()).getClaimAsString("sid"))
				.isNotEqualTo(decoder.decode(second.accessToken()).getClaimAsString("sid"));
		assertThat(redis.keys("auth:refresh:*")).hasSize(2);
		assertThat(redis.opsForZSet().zCard("auth:user:" + userId + ":sessions")).isEqualTo(2L);
	}

	@ParameterizedTest
	@ValueSource(strings = {"wrong-password", "missing-email", "social-only", "withdrawal", "orphan-auth"})
	void credentialAndAccountFailuresHaveSameResponseAndNeverCreateSession(String scenario) throws Exception {
		String body = BODY;
		switch (scenario) {
			case "wrong-password" -> body = BODY.replace(" Password1 ", "Wrong123");
			case "missing-email" -> body = BODY.replace("Fisher@Example.COM", "missing@example.com");
			case "social-only" -> jdbc.update("UPDATE fishingstory.user_auth SET provider='KAKAO', provider_user_id='social-id', password_hash=NULL, email_verified_at=NULL");
			case "withdrawal" -> jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now()");
			case "orphan-auth" -> jdbc.update("DELETE FROM fishingstory.users");
		}
		mvc.perform(post("/api/auth/login/email").contentType("application/json").content(body))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
				.andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(jsonPath("$.refreshToken").doesNotExist());
		assertThat(redis.keys("auth:refresh:*")).isEmpty();
		verifyNoInteractions(sessions, accessTokens);
	}

	@Test
	void refreshStorageFailureNeverReturnsTokens() throws Exception {
		doThrow(new DataAccessResourceFailureException("private storage detail")).when(sessions).create(anyLong(), anyString(), anyString());
		mvc.perform(post("/api/auth/login/email").contentType("application/json").content(BODY))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(jsonPath("$.refreshToken").doesNotExist());
	}

	@Test
	void signingFailureDoesNotLeaveRefreshSession() throws Exception {
		doThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE)).when(accessTokens).issue(anyLong(), anyString(), anyString(), anyLong());
		mvc.perform(post("/api/auth/login/email").contentType("application/json").content(BODY)).andExpect(status().isServiceUnavailable());
		verifyNoInteractions(sessions);
		assertThat(redis.keys("auth:refresh:*")).isEmpty();
	}

	@Test
	void successfulAndFailedRequestsShareLimitAndSpoofedHeadersDoNotResetIt() throws Exception {
		mvc.perform(post("/api/auth/login/email").contentType("application/json").content(BODY)).andExpect(status().isOk());
		for (int i = 0; i < 9; i++) {
			mvc.perform(post("/api/auth/login/email").contentType("application/json").content(BODY.replace(" Password1 ", "Wrong123"))
					.header("X-Forwarded-For", "192.0.2." + i)).andExpect(status().isUnauthorized());
		}
		mvc.perform(post("/api/auth/login/email").contentType("application/json").content(BODY))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
				.andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.retryAfterSeconds").isNumber());
	}

	@Test
	void protectedRoutesRequireValidBearerWhileTermsRemainPublic() throws Exception {
		mvc.perform(get("/login-test/private")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
				.andExpect(header().string("WWW-Authenticate", "Bearer"));
		mvc.perform(get("/login-test/private").header("Authorization", "Bearer invalid-token"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/terms/current")).andExpect(status().isOk());
		mvc.perform(post("/api/auth/not-an-approved-route")).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshWithoutAccessTokenRotatesAndUsesCurrentDatabaseRole() throws Exception {
		var first = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		jdbc.update("UPDATE fishingstory.users SET role='ADMIN' WHERE user_id=?", userId);
		var response = mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(first.refreshToken()))))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
		var next = mapper.readValue(response.getContentAsString(), EmailLoginService.Tokens.class);
		var jwt = decoder.decode(next.accessToken());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
		assertThat(jwt.getClaimAsString("sid")).isEqualTo(decoder.decode(first.accessToken()).getClaimAsString("sid"));
		assertThat(jwt.getId()).isNotEqualTo(decoder.decode(first.accessToken()).getId());
		assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
		assertThat(sessions.findValid(first.refreshToken())).isEmpty();
		assertThat(sessions.findValid(next.refreshToken())).isPresent();
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(first.refreshToken()))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID_OR_EXPIRED"));
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(next.refreshToken())))).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {"withdrawal", "deleted"})
	void refreshRejectsInactiveOrDeletedMember(String scenario) throws Exception {
		var first = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		if (scenario.equals("deleted")) jdbc.update("DELETE FROM fishingstory.users");
		else jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now()");
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(first.refreshToken()))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID_OR_EXPIRED"))
				.andExpect(jsonPath("$.accessToken").doesNotExist());
		verify(sessions, never()).rotate(anyString(), anyString(), any());
	}

	@Test
	void refreshStorageFailureIsSanitizedAndDoesNotReturnTokens() throws Exception {
		var first = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		doThrow(new DataAccessResourceFailureException("private storage detail")).when(sessions).rotate(anyString(), anyString(), any());
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(first.refreshToken()))))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private storage detail"))));
		assertThat(sessions.findValid(first.refreshToken())).isPresent();
	}

	@Test
	void refreshRejectsMalformedAndUnknownTokensWithoutAccessAuthentication() throws Exception {
		for (String body : new String[] {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"bad token\"}"}) {
			mvc.perform(post("/api/auth/refresh").contentType("application/json").content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand("X".repeat(43)))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID_OR_EXPIRED"));
	}

	@Test
	void logoutUsesAuthenticatedSessionKeepsOtherDeviceAndAllowsRepeatedRequests() throws Exception {
		var first = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		var other = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.2");
		String otherSid = decoder.decode(other.accessToken()).getClaimAsString("sid");
		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first.accessToken())
				.contentType("application/json").content("{\"userId\":999,\"sessionId\":\"" + otherSid + "\"}"))
				.andExpect(status().isNoContent()).andExpect(content().string(""))
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"));
		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first.accessToken()))
				.andExpect(status().isNoContent());
		assertThat(sessions.findValid(first.refreshToken())).isEmpty();
		assertThat(sessions.findValid(other.refreshToken())).isPresent();
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(first.refreshToken()))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID_OR_EXPIRED"));
		mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(other.refreshToken())))).andExpect(status().isOk());
		// 블랙리스트를 사용하지 않으므로 로그아웃 전 Access는 원래 만료까지 유효하다.
		mvc.perform(get("/login-test/private").header("Authorization", "Bearer " + first.accessToken())).andExpect(status().isOk());
	}

	@Test
	void logoutWithPreRotationAccessTokenRemovesLatestRefreshToken() throws Exception {
		var first = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		var response = mvc.perform(post("/api/auth/refresh").contentType("application/json")
				.content(mapper.writeValueAsString(new RefreshTokenCommand(first.refreshToken()))))
				.andExpect(status().isOk()).andReturn().getResponse();
		var next = mapper.readValue(response.getContentAsString(), EmailLoginService.Tokens.class);
		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first.accessToken())).andExpect(status().isNoContent());
		assertThat(sessions.findValid(next.refreshToken())).isEmpty();
		assertThat(redis.hasKey("auth:user:" + userId + ":sessions")).isFalse();
	}

	@Test
	void logoutRequiresValidAccessAuthenticationAndNeverAcceptsRefreshAsBearer() throws Exception {
		var tokens = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		for (String bearer : new String[] {"invalid-token", tokens.refreshToken()}) {
			mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + bearer))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		}
		verify(sessions, never()).logout(anyLong(), anyString());
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();
	}

	@Test
	void logoutStorageFailureIsNotReportedAsSuccessAndCanBeRetried() throws Exception {
		var tokens = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		doThrow(new DataAccessResourceFailureException("private storage detail")).doCallRealMethod()
				.when(sessions).logout(anyLong(), anyString());
		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + tokens.accessToken()))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private storage detail"))));
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();
		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + tokens.accessToken())).andExpect(status().isNoContent());
		assertThat(sessions.findValid(tokens.refreshToken())).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"withdrawal", "deleted"})
	void logoutCanEndSessionEvenWhenMemberIsNoLongerActive(String scenario) throws Exception {
		var tokens = login.login(new EmailLoginCommand(EMAIL, PASSWORD), "192.0.2.1");
		if (scenario.equals("deleted")) jdbc.update("DELETE FROM fishingstory.users");
		else jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now()");
		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + tokens.accessToken())).andExpect(status().isNoContent());
		assertThat(sessions.findValid(tokens.refreshToken())).isEmpty();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Containers {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
		@Bean @ServiceConnection(name = "redis")
		GenericContainer<?> valkey() { return new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379); }
		@Bean ProtectedEndpoint protectedEndpoint() { return new ProtectedEndpoint(); }
	}
	@RestController
	@org.springframework.boot.test.context.TestComponent
	static class ProtectedEndpoint {
		@GetMapping("/login-test/private")
		String identity(Authentication authentication) {
			// Authentication factors are separate from application roles; neither collection order nor factor position is a contract.
			String roles = authentication.getAuthorities().stream()
					.map(org.springframework.security.core.GrantedAuthority::getAuthority)
					.filter(authority -> authority.startsWith("ROLE_"))
					.sorted().collect(java.util.stream.Collectors.joining(","));
			return authentication.getName() + ":" + roles;
		}
	}
}
