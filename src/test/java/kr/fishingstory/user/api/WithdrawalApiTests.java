package kr.fishingstory.user.api;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import kr.fishingstory.mail.EmailSender;
import kr.fishingstory.user.domain.WithdrawalPolicy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(WithdrawalApiTests.Containers.class)
class WithdrawalApiTests {
	static final long USER_ID = 9007199254740993L;
	static final String EMAIL = "fisher@example.com";
	static final String PASSWORD = "Password1";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired PasswordEncoder passwords;
	@Autowired EmailLoginService login;
	@MockitoBean EmailSender sender;
	@MockitoSpyBean RefreshSessionStore sessions;

	@BeforeEach
	void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, nickname_changed_at, age_confirmed_at) VALUES (?, 'Fisher', 'fisher', now(), now())", USER_ID);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', ?, ?, now())",
				USER_ID + 1, USER_ID, EMAIL, passwords.encode(PASSWORD));
		when(sender.send(any(EmailSender.Message.class), any(BooleanSupplier.class)))
				.thenReturn(new EmailSender.Result(EmailSender.Status.ACCEPTED, "mail-id"));
	}

	@Test
	void requestEndsEverySessionAndEmailVerificationRestoresAccount() throws Exception {
		var first = login("192.0.2.1");
		var second = login("192.0.2.2");
		String body = mvc.perform(withdraw(first.accessToken(), true)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andReturn().getResponse().getContentAsString();
		Instant requestedAt = Instant.parse(json.readTree(body).get("withdrawalRequestedAt").asText());
		Instant deadline = Instant.parse(json.readTree(body).get("cancellationDeadline").asText());
		assertThat(Duration.between(requestedAt, deadline)).isEqualTo(Duration.ofDays(30));
		assertThat(accountStatus()).isEqualTo("WITHDRAW_PENDING");
		assertThat(sessions.findValid(first.refreshToken())).isEmpty();
		assertThat(sessions.findValid(second.refreshToken())).isEmpty();
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + first.accessToken()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		assertThatThrownBy(() -> login("192.0.2.3")).isInstanceOf(ApiException.class).hasMessage("LOGIN_FAILED");

		String token = requestAndVerifyCancellation(EMAIL);
		mvc.perform(cancel(EMAIL, token)).andExpect(status().isNoContent())
				.andExpect(header().string("Cache-Control", "no-store"));
		assertThat(accountStatus()).isEqualTo("ACTIVE");
		assertThat(withdrawalRequestedAt()).isNull();
		for (var previous : List.of(first, second)) {
			mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + previous.accessToken()))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		}
		var recovered = login("192.0.2.4");
		assertThat(recovered.refreshToken()).isNotBlank();
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + recovered.accessToken()))
				.andExpect(status().isOk());
		mvc.perform(cancel(EMAIL, token)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("WITHDRAWAL_CANCELLATION_INVALID_OR_EXPIRED"));
	}

	@Test
	void repeatedRequestKeepsOriginalDeadlineAndConfirmationIsRequired() throws Exception {
		mvc.perform(post("/api/users/me/withdrawal").contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirmed\":true}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		var tokens = login("192.0.2.1");
		mvc.perform(withdraw(tokens.accessToken(), false)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(accountStatus()).isEqualTo("ACTIVE");

		String first = mvc.perform(withdraw(tokens.accessToken(), true)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String second = mvc.perform(withdraw(tokens.accessToken(), true)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(second).get("withdrawalRequestedAt").asText())
				.isEqualTo(json.readTree(first).get("withdrawalRequestedAt").asText());
		assertThat(json.readTree(second).get("cancellationDeadline").asText())
				.isEqualTo(json.readTree(first).get("cancellationDeadline").asText());
	}

	@Test
	void sessionFailureRollsBackNewRequestAndPreservesSession() throws Exception {
		var tokens = login("192.0.2.1");
		doThrow(new DataAccessResourceFailureException("private storage detail")).when(sessions).logoutAll(USER_ID);
		mvc.perform(withdraw(tokens.accessToken(), true)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private storage detail"))));
		assertThat(accountStatus()).isEqualTo("ACTIVE");
		assertThat(sessions.findValid(tokens.refreshToken())).isPresent();
	}

	@Test
	void concurrentLoginCannotLeaveRefreshSessionAfterWithdrawal() throws Exception {
		var current = login("192.0.2.1");
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var withdrawal = executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return mvc.perform(withdraw(current.accessToken(), true)).andReturn().getResponse().getStatus();
			});
			var concurrentLogin = executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				try { login("192.0.2.2"); return true; }
				catch (ApiException rejected) { assertThat(rejected).hasMessage("LOGIN_FAILED"); return false; }
			});
			start.countDown();
			assertThat(withdrawal.get(30, TimeUnit.SECONDS)).isEqualTo(200);
			concurrentLogin.get(30, TimeUnit.SECONDS);
		}
		assertThat(accountStatus()).isEqualTo("WITHDRAW_PENDING");
		assertThat(redis.keys("auth:refresh:*")).isEmpty();
	}

	@Test
	void cancellationRequestDoesNotRevealAccountStateOrProviderFailure() throws Exception {
		String active = mvc.perform(sendCancellation(EMAIL)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(active).get("status").asText()).isEqualTo("ACCEPTED");
		verify(sender, never()).send(any(EmailSender.Message.class), any(BooleanSupplier.class));

		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		when(sender.send(any(EmailSender.Message.class), any(BooleanSupplier.class)))
				.thenReturn(new EmailSender.Result(EmailSender.Status.REJECTED, null));
		String pending = mvc.perform(sendCancellation(EMAIL)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(pending).get("status").asText()).isEqualTo("ACCEPTED");
		assertThat(json.readTree(pending).get("message").asText())
				.isEqualTo(json.readTree(active).get("message").asText());
		assertThat(redis.keys("*:withdrawal-cancel-code")).isEmpty();
	}

	@Test
	void exactThirtyDayBoundaryRejectsCancellationWithoutConsumingTokenEarly() throws Exception {
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		String token = requestAndVerifyCancellation(EMAIL);
		Instant requestedAt = Instant.now().minus(WithdrawalPolicy.GRACE_PERIOD);
		assertThat(WithdrawalPolicy.canCancel(requestedAt, requestedAt.plus(WithdrawalPolicy.GRACE_PERIOD))).isFalse();
		assertThat(WithdrawalPolicy.canCancel(requestedAt,
				requestedAt.plus(WithdrawalPolicy.GRACE_PERIOD).minusNanos(1))).isTrue();
		jdbc.update("UPDATE fishingstory.users SET withdrawal_requested_at=? WHERE user_id=?",
				OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC), USER_ID);
		mvc.perform(cancel(EMAIL, token)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("WITHDRAWAL_CANCELLATION_INVALID_OR_EXPIRED"));
		assertThat(accountStatus()).isEqualTo("WITHDRAW_PENDING");
	}

	@Test
	void recoveryVerificationDoesNotRevealWhetherACodeExists() throws Exception {
		mvc.perform(sendCancellation(EMAIL)).andExpect(status().isOk());
		mvc.perform(verifyCancellationCode(EMAIL, "999999")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED"));

		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		requestCancellationCode(EMAIL);
		mvc.perform(verifyCancellationCode(EMAIL, "999999")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED"));
	}

	@Test
	void cancellationPurposeCannotBeCrossedWithPasswordReset() throws Exception {
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		String code = requestCancellationCode(EMAIL);
		mvc.perform(post("/api/auth/password-reset/email-code/verify").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", EMAIL, "code", code))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED"));
		mvc.perform(verifyCancellationCode(EMAIL, code)).andExpect(status().isOk())
				.andExpect(jsonPath("$.cancellationToken").exists())
				.andExpect(jsonPath("$.resetToken").doesNotExist());
	}

	private String requestAndVerifyCancellation(String email) throws Exception {
		String code = requestCancellationCode(email);
		String body = mvc.perform(verifyCancellationCode(email, code)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("cancellationToken").asText();
	}
	private String requestCancellationCode(String email) throws Exception {
		mvc.perform(sendCancellation(email)).andExpect(status().isOk());
		var captor = org.mockito.ArgumentCaptor.forClass(EmailSender.Message.class);
		verify(sender).send(captor.capture(), any(BooleanSupplier.class));
		return captor.getValue().text().replaceAll("(?s).*인증코드는 ([0-9]{6})입니다.*", "$1");
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withdraw(
			String accessToken, boolean confirmed) throws Exception {
		return post("/api/users/me/withdrawal").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("confirmed", confirmed)));
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sendCancellation(String email) throws Exception {
		return post("/api/auth/withdrawal-cancel/email-code").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email)));
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verifyCancellationCode(
			String email, String code) throws Exception {
		return post("/api/auth/withdrawal-cancel/email-code/verify").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email, "code", code)));
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancel(String email, String token) throws Exception {
		return post("/api/auth/withdrawal-cancel").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email, "cancellationToken", token)));
	}
	private EmailLoginService.Tokens login(String ip) {
		return login.login(new EmailLoginCommand(EMAIL, PASSWORD), ip);
	}
	private String accountStatus() {
		return jdbc.queryForObject("SELECT status FROM fishingstory.users WHERE user_id=?", String.class, USER_ID);
	}
	private Instant withdrawalRequestedAt() {
		return jdbc.queryForObject("SELECT withdrawal_requested_at FROM fishingstory.users WHERE user_id=?",
				(rs, row) -> rs.getObject(1, java.time.OffsetDateTime.class) == null ? null
						: rs.getObject(1, java.time.OffsetDateTime.class).toInstant(), USER_ID);
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
