package kr.fishingstory.auth.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.api.EmailSignupController;
import kr.fishingstory.auth.infrastructure.jpa.SignupMemberStore;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.auth.infrastructure.valkey.SignupEmailCodeStore;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.error.GlobalExceptionHandler;
import kr.fishingstory.common.id.SnowflakeIdGenerator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmailSignupIntegrationTests.Containers.class)
class EmailSignupIntegrationTests {
	static final String EMAIL = "fisher@example.com";
	static final String TOKEN = "A".repeat(43);
	static final String PASSWORD = " Test1234 ";
	@Autowired EmailSignupService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired EmailSendQuota quota;
	@Autowired SignupEmailCodeStore codes;
	@Autowired PasswordEncoder passwords;
	@MockitoSpyBean SignupVerificationStore verification;
	@MockitoSpyBean SignupMemberStore members;
	@MockitoSpyBean SnowflakeIdGenerator ids;
	MockMvc mvc;

	@BeforeEach
	void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("DELETE FROM fishingstory.terms");
		try (var client = redis.getConnectionFactory().getConnection()) { client.serverCommands().flushDb(); }
		jdbc.update("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, requires_consent, is_required, effective_at, published_at)
				VALUES
				(11, 'TERMS_OF_SERVICE', 'old', 'Old', 'Old content', true, true, now() - interval '2 days', now() - interval '3 days'),
				(12, 'TERMS_OF_SERVICE', 'current', 'Current', 'Current content', true, true, now() - interval '1 day', now() - interval '2 days'),
				(13, 'TERMS_OF_SERVICE', 'future', 'Future', 'Future content', true, true, now() + interval '1 day', now()),
				(14, 'TERMS_OF_SERVICE', 'draft', 'Draft', 'Draft content', true, true, now() - interval '1 hour', null),
				(20, 'PRIVACY_POLICY', 'current', 'Privacy', 'Privacy content', false, false, now() - interval '1 day', now() - interval '2 days')
				""");
		mvc = MockMvcBuilders.standaloneSetup(new EmailSignupController(service))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@Test
	void apiCreatesAllRowsWithServerControlledFieldsAndConsumesGrantAfterCommit() throws Exception {
		Instant expiry = issue(EMAIL);
		doAnswer(call -> {
			assertRows(1);
			return call.callRealMethod();
		}).when(verification).consume(EMAIL, TOKEN);
		mvc.perform(post("/api/auth/signup/email").contentType("application/json").content("""
				{"email":" Fisher@Example.COM ","signupToken":"%s","password":" Test1234 ",
				 "passwordConfirmation":" Test1234 ","nickname":"  낚시  Fisher  ","ageConfirmed":true,"agreedTermsId":12}
				""".formatted(TOKEN)))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.userId").isNumber()).andExpect(jsonPath("$.nickname").value("낚시 Fisher"))
				.andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.signupToken").doesNotExist())
				.andExpect(jsonPath("$.accessToken").doesNotExist());
		var user = jdbc.queryForMap("SELECT * FROM fishingstory.users");
		assertThat(user).containsEntry("nickname_key", "낚시 fisher").containsEntry("role", "USER")
				.containsEntry("status", "ACTIVE").containsEntry("withdrawal_requested_at", null);
		assertThat(user.get("age_confirmed_at")).isEqualTo(user.get("created_at"));
		assertThat(user.get("nickname_changed_at")).isEqualTo(user.get("created_at"));
		var auth = jdbc.queryForMap("SELECT * FROM fishingstory.user_auth");
		assertThat(auth).containsEntry("user_id", user.get("user_id")).containsEntry("email", EMAIL)
				.containsEntry("provider", "EMAIL").containsEntry("provider_user_id", null);
		String hash = (String) auth.get("password_hash");
		assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
		assertThat(passwords.matches(PASSWORD, hash)).isTrue();
		assertThat(passwords.matches(PASSWORD.strip(), hash)).isFalse();
		Instant verifiedAt = jdbc.queryForObject("SELECT email_verified_at FROM fishingstory.user_auth", (rs, row) -> rs.getTimestamp(1).toInstant());
		assertThat(verifiedAt).isEqualTo(expiry.minus(Duration.ofHours(1)));
		assertThat(jdbc.queryForMap("SELECT user_id, terms_id, action FROM fishingstory.user_terms_agreement_history"))
				.containsEntry("user_id", user.get("user_id")).containsEntry("terms_id", 12L).containsEntry("action", "AGREED");
		assertThat(verification.findValid(EMAIL, TOKEN)).isEmpty();
	}

	@Test
	void databaseFailureRollsBackAllRowsAndSameGrantCanRetry() {
		Instant expiry = issue(EMAIL);
		doAnswer(call -> {
			call.callRealMethod();
			throw new DataAccessResourceFailureException("simulated failure after flush");
		}).when(members).insert(any(), any(), any());
		assertThatThrownBy(() -> service.signup(command(EMAIL, "Fisher", 12L))).isInstanceOf(DataAccessResourceFailureException.class);
		assertRows(0);
		assertThat(verification.findValid(EMAIL, TOKEN)).contains(expiry);
		verify(verification, never()).consume(anyString(), anyString());
		doCallRealMethod().when(members).insert(any(), any(), any());
		service.signup(command(EMAIL, "Fisher", 12L));
		assertRows(1);
	}

	@Test
	void cleanupFailureAndLostSuccessResponseNeverOverwriteExistingMember() {
		Instant expiry = issue(EMAIL);
		doThrow(new DataAccessResourceFailureException("cleanup unavailable")).when(verification).consume(EMAIL, TOKEN);
		var first = service.signup(command(EMAIL, "Fisher", 12L));
		String originalHash = jdbc.queryForObject("SELECT password_hash FROM fishingstory.user_auth", String.class);
		assertThat(verification.findValid(EMAIL, TOKEN)).contains(expiry);
		var retry = new EmailSignupCommand(EMAIL, TOKEN, "Changed123", "Changed123", "New Name", true, 12L);
		assertRejected(retry, ErrorCode.EMAIL_ALREADY_REGISTERED);
		assertRows(1);
		assertThat(jdbc.queryForObject("SELECT user_id FROM fishingstory.users", Long.class)).isEqualTo(first.userId());
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users", String.class)).isEqualTo("Fisher");
		assertThat(jdbc.queryForObject("SELECT password_hash FROM fishingstory.user_auth", String.class)).isEqualTo(originalHash);
	}

	@ParameterizedTest
	@ValueSource(longs = {11, 13, 14, 20, 999})
	void onlyExplicitConsentToCurrentTermsIsAccepted(long termsId) {
		Instant expiry = issue(EMAIL);
		assertRejected(command(EMAIL, "Fisher", termsId), ErrorCode.SIGNUP_TERMS_CHANGED);
		assertRows(0);
		assertThat(verification.findValid(EMAIL, TOKEN)).contains(expiry);
	}

	@Test
	void noPublishedEffectiveTermsPreventsSignup() {
		issue(EMAIL);
		jdbc.update("DELETE FROM fishingstory.terms WHERE terms_id IN (11, 12)");
		assertRejected(command(EMAIL, "Fisher", 12L), ErrorCode.SIGNUP_TERMS_UNAVAILABLE);
		assertRows(0);
	}

	@Test
	void wrongEmailAndConsumedGrantCannotCreateMember() {
		issue(EMAIL);
		assertRejected(command("other@example.com", "Fisher", 12L), ErrorCode.SIGNUP_VERIFICATION_INVALID_OR_EXPIRED);
		service.signup(command(EMAIL, "Fisher", 12L));
		assertRejected(command(EMAIL, "Changed", 12L), ErrorCode.SIGNUP_VERIFICATION_INVALID_OR_EXPIRED);
		assertRows(1);
	}

	@Test
	void grantExpiringDuringHashingIsRejectedBeforeAnyInsert() {
		Instant expiry = issue(EMAIL);
		doReturn(java.util.Optional.of(expiry), java.util.Optional.empty()).when(verification).findValid(EMAIL, TOKEN);
		assertRejected(command(EMAIL, "Fisher", 12L), ErrorCode.SIGNUP_VERIFICATION_INVALID_OR_EXPIRED);
		verify(members, never()).insert(any(), any(), any());
		verify(verification, never()).consume(anyString(), anyString());
		assertRows(0);
	}

	@Test
	void storageOutageFailsClosed() {
		doThrow(new DataAccessResourceFailureException("unavailable")).when(verification).findValid(EMAIL, TOKEN);
		assertThatThrownBy(() -> service.signup(command(EMAIL, "Fisher", 12L))).isInstanceOf(DataAccessResourceFailureException.class);
		verify(members, never()).insert(any(), any(), any());
		assertRows(0);
	}

	@Test
	void primaryKeyCollisionNeverMergesExistingUser() {
		issue(EMAIL);
		var existing = service.signup(command(EMAIL, "Fisher", 12L));
		issue("other@example.com");
		doReturn(existing.userId()).when(ids).nextId();
		assertThatThrownBy(() -> service.signup(command("other@example.com", "Other", 12L))).isInstanceOf(RuntimeException.class);
		assertRows(1);
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users", String.class)).isEqualTo("Fisher");
		assertThat(verification.findValid("other@example.com", TOKEN)).isPresent();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void concurrentEmailOrNicknameCollisionCommitsExactlyOneCompleteMember(boolean sameEmail) throws Exception {
		issue(EMAIL);
		String secondEmail = sameEmail ? EMAIL : "other@example.com";
		if (!sameEmail) issue(secondEmail);
		var first = command(EMAIL, "Fisher", 12L);
		var second = command(secondEmail, sameEmail ? "Other" : "FISHER", 12L);
		var barrier = new CyclicBarrier(2);
		doAnswer(call -> {
			barrier.await(15, TimeUnit.SECONDS);
			return call.callRealMethod();
		}).when(members).insert(any(), any(), any());
		try (var executor = Executors.newFixedThreadPool(2)) {
			List<Callable<Object>> tasks = List.of(() -> attempt(first), () -> attempt(second));
			var futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
			var results = List.of(futures.get(0).get(), futures.get(1).get());
			assertThat(results).filteredOn(EmailSignupService.Result.class::isInstance).hasSize(1);
			assertThat(results).contains(sameEmail ? ErrorCode.EMAIL_ALREADY_REGISTERED : ErrorCode.NICKNAME_ALREADY_USED);
		}
		assertRows(1);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM fishingstory.users u
				JOIN fishingstory.user_auth a ON a.user_id = u.user_id
				JOIN fishingstory.user_terms_agreement_history h ON h.user_id = u.user_id
				WHERE h.terms_id = 12 AND h.action = 'AGREED'
				""", Integer.class)).isEqualTo(1);
	}

	private Object attempt(EmailSignupCommand command) {
		try { return service.signup(command); }
		catch (ApiException failure) { return failure.errorCode(); }
	}
	private Instant issue(String email) {
		codes.replace(email, quota.reserve(email, "192.0.2.1").reservation(), "001234");
		return verification.verify(email, "001234", TOKEN);
	}
	private EmailSignupCommand command(String email, String nickname, Long termsId) {
		return new EmailSignupCommand(email, TOKEN, PASSWORD, PASSWORD, nickname, true, termsId);
	}
	private void assertRejected(EmailSignupCommand command, ErrorCode code) {
		assertThatThrownBy(() -> service.signup(command)).isInstanceOfSatisfying(ApiException.class,
				failure -> assertThat(failure.errorCode()).isEqualTo(code));
	}
	private void assertRows(int count) {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.users", Integer.class)).isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_auth", Integer.class)).isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_terms_agreement_history", Integer.class)).isEqualTo(count);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Containers {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
		@Bean @ServiceConnection(name = "redis")
		GenericContainer<?> valkey() {
			return new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
		}
	}
}
