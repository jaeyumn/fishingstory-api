package kr.fishingstory.auth.api;

import java.util.Map;
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
import kr.fishingstory.mail.EmailSender;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(PasswordResetApiTests.Containers.class)
class PasswordResetApiTests {
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
				USER_ID + 1, USER_ID, EMAIL, passwords.encode(CURRENT));
		when(sender.send(any(EmailSender.Message.class), any(BooleanSupplier.class)))
				.thenReturn(new EmailSender.Result(EmailSender.Status.ACCEPTED, "mail-id"));
	}

	@Test
	void resetsPasswordEndsEverySessionAndRejectsTokenReplay() throws Exception {
		var first = login(CURRENT, "192.0.2.10");
		var second = login(CURRENT, "192.0.2.11");
		String token = requestAndVerify(EMAIL);

		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isNoContent())
				.andExpect(header().string("Cache-Control", "no-store"));
		assertThat(passwords.matches(NEXT, passwordHash())).isTrue();
		assertThat(sessions.findValid(first.refreshToken())).isEmpty();
		assertThat(sessions.findValid(second.refreshToken())).isEmpty();
		mvc.perform(reset(EMAIL, token, "ThirdPassword3", "ThirdPassword3"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID_OR_EXPIRED"));
	}

	@Test
	void requestDoesNotRevealMissingOrInactiveAccount() throws Exception {
		var existing = mvc.perform(send(EMAIL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		clearInvocations(sender);
		var missing = mvc.perform(send("missing@example.com")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(existing).get("status").asText()).isEqualTo("ACCEPTED");
		assertThat(json.readTree(missing).get("status").asText()).isEqualTo("ACCEPTED");
		assertThat(json.readTree(missing).get("message").asText())
				.isEqualTo(json.readTree(existing).get("message").asText());
		assertThat(confirmedEmailQuotaEntries()).isEqualTo(2);
		verify(sender, never()).send(any(EmailSender.Message.class), any(BooleanSupplier.class));

		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		mvc.perform(send(EMAIL)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		verify(sender, never()).send(any(EmailSender.Message.class), any(BooleanSupplier.class));
	}

	@Test
	void signupAndPasswordResetPurposesCannotBeCrossed() throws Exception {
		String code = requestCode(EMAIL);
		mvc.perform(post("/api/auth/signup/email-code/verify").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", EMAIL, "code", code))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMAIL_CODE_INVALID_OR_EXPIRED"));
		mvc.perform(verifyCode(EMAIL, code)).andExpect(status().isOk()).andExpect(jsonPath("$.resetToken").exists())
				.andExpect(jsonPath("$.signupToken").doesNotExist());
	}

	@Test
	void definiteProviderFailureIsAlsoMaskedAndLeavesNoUsableCode() throws Exception {
		when(sender.send(any(EmailSender.Message.class), any(BooleanSupplier.class)))
				.thenReturn(new EmailSender.Result(EmailSender.Status.REJECTED, null));
		mvc.perform(send(EMAIL)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(redis.keys("*:password-reset-code")).isEmpty();
		assertThat(confirmedEmailQuotaEntries()).isEqualTo(1);
	}

	@Test
	void recoveryVerificationDoesNotRevealWhetherACodeExists() throws Exception {
		mvc.perform(send("missing@example.com")).andExpect(status().isOk());
		mvc.perform(verifyCode("missing@example.com", "999999")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED"));

		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		requestCode(EMAIL);
		mvc.perform(verifyCode(EMAIL, "999999")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED"));
	}

	@Test
	void unchangedPasswordDoesNotConsumeResetToken() throws Exception {
		String token = requestAndVerify(EMAIL);
		mvc.perform(reset(EMAIL, token, CURRENT, CURRENT)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));
		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isNoContent());
	}

	@Test
	void invalidTokenCannotBeUsedToGuessTheCurrentPassword() throws Exception {
		String invalidToken = "X".repeat(43);
		for (String password : new String[] {CURRENT, NEXT}) {
			mvc.perform(reset(EMAIL, invalidToken, password, password))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID_OR_EXPIRED"));
		}
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
	}

	@Test
	void consumedTokenCannotConfirmTheNewPassword() throws Exception {
		String token = requestAndVerify(EMAIL);
		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isNoContent());
		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID_OR_EXPIRED"));
	}

	@Test
	void validatesInputsBeforeConsumingToken() throws Exception {
		String token = requestAndVerify(EMAIL);
		mvc.perform(reset(EMAIL, token, "short1", "short1")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(reset(EMAIL, token, NEXT, "Mismatch3")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isNoContent());
	}

	@Test
	void sessionFailureRollsBackPasswordButConsumesResetToken() throws Exception {
		String token = requestAndVerify(EMAIL);
		doThrow(new DataAccessResourceFailureException("private storage detail")).when(sessions).logoutAll(USER_ID);
		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private storage detail"))));
		assertThat(passwords.matches(CURRENT, passwordHash())).isTrue();
		mvc.perform(reset(EMAIL, token, NEXT, NEXT)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID_OR_EXPIRED"));
	}

	private String requestAndVerify(String email) throws Exception {
		String code = requestCode(email);
		String body = mvc.perform(verifyCode(email, code)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("resetToken").asText();
	}
	private String requestCode(String email) throws Exception {
		mvc.perform(send(email)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		var captor = org.mockito.ArgumentCaptor.forClass(EmailSender.Message.class);
		verify(sender).send(captor.capture(), any(BooleanSupplier.class));
		return captor.getValue().text().replaceAll("(?s).*인증코드는 ([0-9]{6})입니다.*", "$1");
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(String email) throws Exception {
		return post("/api/auth/password-reset/email-code").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email)));
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verifyCode(String email, String code) throws Exception {
		return post("/api/auth/password-reset/email-code/verify").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email, "code", code)));
	}
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reset(
			String email, String token, String password, String confirmation) throws Exception {
		return post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email, "resetToken", token,
						"newPassword", password, "newPasswordConfirmation", confirmation)));
	}
	private EmailLoginService.Tokens login(String password, String ip) {
		return login.login(new EmailLoginCommand(EMAIL, password), ip);
	}
	private String passwordHash() {
		return jdbc.queryForObject("SELECT password_hash FROM fishingstory.user_auth WHERE user_id=?", String.class, USER_ID);
	}
	private long confirmedEmailQuotaEntries() {
		return redis.keys("auth:mail:email:*").stream()
				.filter(key -> key.matches("auth:mail:email:[0-9a-f]{64}"))
				.mapToLong(key -> redis.opsForZSet().size(key)).sum();
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
