package kr.fishingstory.auth.infrastructure.valkey;

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

class PasswordResetVerificationStoreTests {
	static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
	static final String EMAIL = "fisher@example.com";
	static final String TOKEN = "R".repeat(43);
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	static EmailSendQuota quota;
	static PasswordResetEmailCodeStore codes;
	static PasswordResetVerificationStore verification;

	@BeforeAll
	static void start() {
		VALKEY.start();
		connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		quota = new EmailSendQuota(redis);
		codes = new PasswordResetEmailCodeStore(redis);
		verification = new PasswordResetVerificationStore(redis);
	}
	@AfterAll
	static void stop() {
		if (connection != null) connection.destroy();
		VALKEY.stop();
	}
	@BeforeEach
	void prepare() {
		try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
		codes.replace(EMAIL, quota.reserve(EMAIL, "192.0.2.1").reservation(), "001234");
	}

	@Test
	void grantIsEmailBoundHashedAndSingleUse() {
		verification.verify(EMAIL, "001234", TOKEN);
		String key = PasswordResetVerificationStore.grantKey(EMAIL, TOKEN);
		assertThat(key).doesNotContain(EMAIL, TOKEN).contains(":password-reset-grant:");
		assertThat(verification.consume("other@example.com", TOKEN)).isFalse();
		assertThat(verification.consume(EMAIL, TOKEN)).isTrue();
		assertThat(verification.consume(EMAIL, TOKEN)).isFalse();
	}

	@Test
	void simultaneousConsumptionSucceedsExactlyOnce() throws Exception {
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
	void validityCheckIsEmailBoundNonDestructiveAndRejectsExpiredOrConsumedGrants() {
		assertThat(verification.isValid(EMAIL, TOKEN)).isFalse();
		verification.verify(EMAIL, "001234", TOKEN);
		assertThat(verification.isValid("other@example.com", TOKEN)).isFalse();
		assertThat(verification.isValid(EMAIL, "X".repeat(43))).isFalse();
		assertThat(verification.isValid(EMAIL, TOKEN)).isTrue();
		assertThat(verification.isValid(EMAIL, TOKEN)).isTrue();
		assertThat(verification.consume(EMAIL, TOKEN)).isTrue();
		assertThat(verification.isValid(EMAIL, TOKEN)).isFalse();
		String key = PasswordResetVerificationStore.grantKey(EMAIL, TOKEN);
		redis.opsForValue().set(key, "grant");
		redis.expireAt(key, java.time.Instant.now().minusSeconds(1));
		assertThat(verification.isValid(EMAIL, TOKEN)).isFalse();
	}

	@Test
	void signupCodeCannotIssuePasswordResetGrant() {
		try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
		new SignupEmailCodeStore(redis).replace(EMAIL, quota.reserve(EMAIL, "192.0.2.1").reservation(), "001234");
		assertThatThrownBy(() -> verification.verify(EMAIL, "001234", TOKEN))
				.hasMessage("RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED");
	}
}
