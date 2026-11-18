package kr.fishingstory.auth.infrastructure.valkey;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignupEmailCodeStoreTests {
	static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
	static final String EMAIL = "fisher@example.com";
	static final String EMAIL_KEY = EmailSendQuota.emailKey(EMAIL);
	static final String CODE_KEY = SignupEmailCodeStore.codeKey(EMAIL_KEY);
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	static EmailSendQuota quota;
	static SignupEmailCodeStore codes;

	@BeforeAll
	static void start() {
		VALKEY.start();
		connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		quota = new EmailSendQuota(redis);
		codes = new SignupEmailCodeStore(redis);
	}
	@AfterAll
	static void stop() {
		if (connection != null) connection.destroy();
		VALKEY.stop();
	}
	@BeforeEach
	void clearIsolatedDatabase() {
		try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
	}
	@Test
	void storesLeadingZeroCodeAndFiveMinuteExpiry() {
		var reservation = reserve();
		var expiry = codes.replace(EMAIL, reservation, "001234");
		assertThat(redis.opsForHash().entries(CODE_KEY)).containsEntry("code", "001234")
				.containsEntry("operationId", reservation.id()).containsEntry("attempts", "0")
				.containsEntry("expiresAt", Long.toString(expiry.toEpochMilli()));
		assertThat(redis.getExpire(CODE_KEY)).isBetween(290L, 300L);
	}
	@Test
	void replacementResetsAttemptsAndLateFailureCannotDeleteIt() {
		var first = reserve();
		codes.replace(EMAIL, first, "123456");
		redis.opsForHash().put(CODE_KEY, "attempts", "4");
		var second = reserve();
		codes.replace(EMAIL, second, "654321");
		assertThat(codes.isCurrent(EMAIL, first)).isFalse();
		assertThat(codes.isCurrent(EMAIL, second)).isTrue();
		assertThat(codes.discard(EMAIL, first)).isFalse();
		assertThat(redis.opsForHash().entries(CODE_KEY)).containsEntry("code", "654321")
				.containsEntry("attempts", "0").containsEntry("operationId", second.id());
		assertThat(codes.discard(EMAIL, second)).isTrue();
		assertThat(redis.hasKey(CODE_KEY)).isFalse();
	}
	@Test
	void repeatedReservationCannotReplaceCodeOrExtendExpiry() {
		var reservation = reserve();
		codes.replace(EMAIL, reservation, "123456");
		redis.expire(CODE_KEY, Duration.ofSeconds(100));
		assertThatThrownBy(() -> codes.replace(EMAIL, reservation, "654321")).isInstanceOf(IllegalStateException.class);
		assertThat(redis.getExpire(CODE_KEY)).isBetween(90L, 100L);
		assertThat(redis.opsForHash().get(CODE_KEY, "code")).isEqualTo("123456");
	}
	@Test
	void oldReservationCannotOverwriteNewerCode() {
		var first = reserve();
		var second = reserve();
		codes.replace(EMAIL, second, "654321");
		assertThatThrownBy(() -> codes.replace(EMAIL, first, "123456")).isInstanceOf(IllegalStateException.class);
		assertThat(redis.opsForHash().get(CODE_KEY, "code")).isEqualTo("654321");
	}
	@Test
	void rejectsWrongEmailAndReleasedReservation() {
		var reservation = reserve();
		assertThatThrownBy(() -> codes.replace("other@example.com", reservation, "123456"))
				.isInstanceOf(IllegalArgumentException.class);
		quota.release(reservation);
		assertThatThrownBy(() -> codes.replace(EMAIL, reservation, "123456")).isInstanceOf(IllegalStateException.class);
		assertThat(redis.hasKey(CODE_KEY)).isFalse();
	}
	@Test
	void invalidCodeDoesNotDestroyExistingCode() {
		var first = reserve();
		codes.replace(EMAIL, first, "123456");
		var second = reserve();
		assertThatThrownBy(() -> codes.replace(EMAIL, second, "12345"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(redis.opsForHash().get(CODE_KEY, "code")).isEqualTo("123456");
	}
	private EmailSendQuota.Reservation reserve() {
		// Fixture removes only the cooldown to represent a later permitted resend.
		redis.delete(EMAIL_KEY + ":cooldown");
		return quota.reserve(EMAIL, "192.0.2.1").reservation();
	}
}
