package kr.fishingstory.auth.infrastructure.valkey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.*;

class SignupVerificationStoreTests {
	static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
	static final String EMAIL = "fisher@example.com";
	static final String TOKEN = "A".repeat(43);
	static final String CODE_KEY = SignupEmailCodeStore.codeKey(EmailSendQuota.emailKey(EMAIL));
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	static EmailSendQuota quota;
	static SignupEmailCodeStore codes;
	static SignupVerificationStore verification;

	@BeforeAll
	static void start() {
		VALKEY.start();
		connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		quota = new EmailSendQuota(redis);
		codes = new SignupEmailCodeStore(redis);
		verification = new SignupVerificationStore(redis);
	}
	@AfterAll
	static void stop() {
		if (connection != null) connection.destroy();
		VALKEY.stop();
	}
	@BeforeEach
	void prepareCode() {
		try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
		codes.replace(EMAIL, quota.reserve(EMAIL, "192.0.2.1").reservation(), "001234");
	}
	@Test
	void successCreatesEmailBoundHashedGrantForOneHourAndConsumesCode() {
		var expiry = verification.verify(EMAIL, "001234", TOKEN);
		String key = SignupVerificationStore.grantKey(EMAIL, TOKEN);
		assertThat(redis.opsForValue().get(key)).isEqualTo(Long.toString(expiry.toEpochMilli()));
		assertThat(redis.getExpire(key)).isBetween(3590L, 3600L);
		assertThat(redis.hasKey(CODE_KEY)).isFalse();
		assertThat(key).doesNotContain(TOKEN, EMAIL);
		assertThat(verification.consume("other@example.com", TOKEN)).isFalse();
		assertThat(verification.consume(EMAIL, "B".repeat(43))).isFalse();
		assertThat(verification.consume(EMAIL, TOKEN)).isTrue();
		assertThat(verification.consume(EMAIL, TOKEN)).isFalse();
	}
	@Test
	void repeatedLookupReturnsOriginalExpiryWithoutConsumingGrantOrExtendingTtl() {
		var expiry = verification.verify(EMAIL, "001234", TOKEN);
		String key = SignupVerificationStore.grantKey(EMAIL, TOKEN);
		redis.expire(key, Duration.ofSeconds(100));
		for (int i = 0; i < 3; i++) {
			assertThat(verification.findValid(EMAIL, TOKEN)).contains(expiry);
		}
		assertThat(redis.opsForValue().get(key)).isEqualTo(Long.toString(expiry.toEpochMilli()));
		assertThat(redis.getExpire(key)).isBetween(90L, 100L);
		assertThat(verification.consume(EMAIL, TOKEN)).isTrue();
		assertThat(verification.findValid(EMAIL, TOKEN)).isEmpty();
	}
	@Test
	void lookupRequiresMatchingEmailAndTokenAndLeavesValidGrantAvailable() {
		assertThat(verification.findValid(EMAIL, TOKEN)).isEmpty();
		var expiry = verification.verify(EMAIL, "001234", TOKEN);
		assertThat(verification.findValid("other@example.com", TOKEN)).isEmpty();
		assertThat(verification.findValid(EMAIL, "B".repeat(43))).isEmpty();
		assertThat(verification.findValid(EMAIL, TOKEN)).contains(expiry);
	}
	@Test
	void lookupRejectsExpiredGrantKey() {
		verification.verify(EMAIL, "001234", TOKEN);
		redis.expire(SignupVerificationStore.grantKey(EMAIL, TOKEN), Duration.ZERO);
		assertThat(verification.findValid(EMAIL, TOKEN)).isEmpty();
	}
	@Test
	void lookupRejectsPastOrInvalidStoredExpiryWithoutChangingGrant() {
		String key = SignupVerificationStore.grantKey(EMAIL, TOKEN);
		for (String value : new String[] { "1", "invalid" }) {
			redis.opsForValue().set(key, value, Duration.ofSeconds(100));
			assertThat(verification.findValid(EMAIL, TOKEN)).isEmpty();
			assertThat(redis.opsForValue().get(key)).isEqualTo(value);
			assertThat(redis.getExpire(key)).isBetween(90L, 100L);
		}
	}
	@Test
	void lookupRejectsMalformedInputWithoutConsumingGrant() {
		var expiry = verification.verify(EMAIL, "001234", TOKEN);
		assertThatThrownBy(() -> verification.findValid(EMAIL, "short")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> verification.findValid(EMAIL, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> verification.findValid(" ", TOKEN)).isInstanceOf(IllegalArgumentException.class);
		assertThat(verification.findValid(EMAIL, TOKEN)).contains(expiry);
	}
	@Test
	void correctCodeAfterFourFailuresStillSucceedsWithoutExtendingCodeTtl() {
		redis.expire(CODE_KEY, Duration.ofSeconds(100));
		for (int i = 0; i < 4; i++) {
			assertThatThrownBy(() -> verification.verify(EMAIL, "999999", TOKEN)).hasMessage("EMAIL_CODE_MISMATCH");
		}
		assertThat(redis.getExpire(CODE_KEY)).isBetween(90L, 100L);
		assertThat(verification.verify(EMAIL, "001234", TOKEN)).isNotNull();
	}
	@Test
	void fifthFailureDeletesCodeAndPreventsCorrectCodeFromIssuingGrant() {
		for (int i = 0; i < 4; i++) assertThatThrownBy(() -> verification.verify(EMAIL, "999999", TOKEN)).hasMessage("EMAIL_CODE_MISMATCH");
		assertThatThrownBy(() -> verification.verify(EMAIL, "999999", TOKEN)).hasMessage("EMAIL_CODE_ATTEMPTS_EXCEEDED");
		assertThat(redis.hasKey(CODE_KEY)).isFalse();
		assertThatThrownBy(() -> verification.verify(EMAIL, "001234", TOKEN)).hasMessage("EMAIL_CODE_INVALID_OR_EXPIRED");
		assertThat(redis.keys("*:signup-grant:*")).isEmpty();
	}
	@Test
	void expiredCodeNeverCreatesGrant() {
		redis.opsForHash().put(CODE_KEY, "expiresAt", "1");
		assertThatThrownBy(() -> verification.verify(EMAIL, "001234", TOKEN)).hasMessage("EMAIL_CODE_INVALID_OR_EXPIRED");
		assertThat(redis.hasKey(CODE_KEY)).isFalse();
		assertThat(redis.keys("*:signup-grant:*")).isEmpty();
	}
	@Test
	void replayCannotExtendGrantAndExpiredGrantCannotBeConsumed() {
		verification.verify(EMAIL, "001234", TOKEN);
		String key = SignupVerificationStore.grantKey(EMAIL, TOKEN);
		redis.expire(key, Duration.ofSeconds(100));
		assertThatThrownBy(() -> verification.verify(EMAIL, "001234", TOKEN)).hasMessage("EMAIL_CODE_INVALID_OR_EXPIRED");
		assertThat(redis.getExpire(key)).isBetween(90L, 100L);
		redis.expire(key, Duration.ZERO);
		assertThat(verification.consume(EMAIL, TOKEN)).isFalse();
	}
	@Test
	void simultaneousCorrectRequestsIssueExactlyOneGrant() throws Exception {
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 16; i++) {
				String token = String.valueOf((char) ('A' + i)).repeat(43);
				tasks.add(() -> {
					try { verification.verify(EMAIL, "001234", token); return true; }
					catch (SignupVerificationStore.Rejected rejected) { return false; }
				});
			}
			int successes = 0;
			for (var result : executor.invokeAll(tasks)) if (result.get()) successes++;
			assertThat(successes).isEqualTo(1);
			assertThat(redis.keys("*:signup-grant:*")).hasSize(1);
		}
	}
	@Test
	void simultaneousConsumptionSucceedsOnlyOnce() throws Exception {
		verification.verify(EMAIL, "001234", TOKEN);
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 16; i++) tasks.add(() -> verification.consume(EMAIL, TOKEN));
			int successes = 0;
			for (var result : executor.invokeAll(tasks)) if (result.get()) successes++;
			assertThat(successes).isEqualTo(1);
		}
	}
	@Test
	void wrongEmailDoesNotConsumeCodeAndGrantKeyCollisionDoesNotOverwriteExistingGrant() {
		assertThatThrownBy(() -> verification.verify("other@example.com", "001234", TOKEN)).hasMessage("EMAIL_CODE_INVALID_OR_EXPIRED");
		String key = SignupVerificationStore.grantKey(EMAIL, TOKEN);
		redis.opsForValue().set(key, "existing", Duration.ofSeconds(100));
		assertThatThrownBy(() -> verification.verify(EMAIL, "001234", TOKEN)).isInstanceOf(IllegalStateException.class);
		assertThat(redis.opsForValue().get(key)).isEqualTo("existing");
		assertThat(redis.opsForHash().get(CODE_KEY, "code")).isEqualTo("001234");
	}
}
