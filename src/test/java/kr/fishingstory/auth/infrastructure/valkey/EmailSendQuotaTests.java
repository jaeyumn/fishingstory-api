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

import static org.assertj.core.api.Assertions.assertThat;

class EmailSendQuotaTests {
	static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	static EmailSendQuota quota;
	static final String EMAIL = "fisher@example.com";
	static final String EMAIL_KEY = EmailSendQuota.emailKey(EMAIL);

	@BeforeAll
	static void start() {
		VALKEY.start();
		connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		quota = new EmailSendQuota(redis);
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
	void failureReturnsEmailAllowanceButKeepsCooldownAndIpCount() {
		var reservation = quota.reserve(EMAIL, "192.0.2.1").reservation();
		assertThat(quota.release(reservation)).isTrue();
		assertThat(quota.release(reservation)).isFalse();
		assertThat(redis.opsForZSet().zCard(EMAIL_KEY)).isZero();
		var blocked = quota.reserve(EMAIL, "192.0.2.1");
		assertThat(blocked.allowed()).isFalse();
		assertThat(blocked.retryAfter()).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(60));
		for (int i = 0; i < 28; i++) assertThat(quota.reserve("fisher" + i + "@example.com", "192.0.2.1").allowed()).isTrue();
		assertThat(quota.reserve("last@example.com", "192.0.2.1").allowed()).isFalse();
	}

	@Test
	void confirmationIsIdempotentAndCannotBeReleased() {
		var reservation = quota.reserve(EMAIL, "192.0.2.1").reservation();
		assertThat(quota.confirm(reservation)).isTrue();
		Double score = redis.opsForZSet().score(EMAIL_KEY, reservation.id());
		assertThat(quota.confirm(reservation)).isTrue();
		assertThat(quota.release(reservation)).isFalse();
		assertThat(redis.opsForZSet().score(EMAIL_KEY, reservation.id())).isEqualTo(score);
		assertThat(redis.opsForZSet().zCard(EMAIL_KEY)).isEqualTo(1);
	}

	@Test
	void ambiguousPendingReservationsCountTowardTenAndExpireInRollingWindow() {
		for (int i = 0; i < 10; i++) {
			// Advance eligibility in this fixture without waiting ten minutes.
			redis.delete(EMAIL_KEY + ":cooldown");
			assertThat(quota.reserve(EMAIL, "192.0.2." + i).allowed()).isTrue();
		}
		redis.delete(EMAIL_KEY + ":cooldown");
		assertThat(quota.reserve(EMAIL, "192.0.2.100").retryAfter()).isGreaterThan(Duration.ofHours(23));
		String oldest = redis.opsForZSet().range(EMAIL_KEY, 0, 0).iterator().next();
		redis.opsForZSet().add(EMAIL_KEY, oldest, System.currentTimeMillis() - Duration.ofHours(25).toMillis());
		assertThat(quota.reserve(EMAIL, "192.0.2.101").allowed()).isTrue();
		assertThat(redis.opsForZSet().zCard(EMAIL_KEY)).isEqualTo(10);
	}

	@Test
	void simultaneousRequestsForSameEmailReserveOnlyOneSlot() throws Exception {
		assertThat(concurrentReservations(true)).isEqualTo(1);
		assertThat(redis.opsForZSet().zCard(EMAIL_KEY)).isEqualTo(1);
	}

	@Test
	void simultaneousRequestsFromSameIpNeverExceedThirty() throws Exception {
		assertThat(concurrentReservations(false)).isEqualTo(30);
	}

	@Test
	void expiredIpEntriesNoLongerBlockRequests() {
		for (int i = 0; i < 30; i++) quota.reserve("fisher" + i + "@example.com", "192.0.2.1");
		String key = redis.keys("auth:mail:ip:*").iterator().next();
		String oldest = redis.opsForZSet().range(key, 0, 0).iterator().next();
		redis.opsForZSet().add(key, oldest, System.currentTimeMillis() - Duration.ofHours(2).toMillis());
		assertThat(quota.reserve(EMAIL, "192.0.2.1").allowed()).isTrue();
	}

	@Test
	void keysHaveBoundedLifetimesAndDoNotContainRawEmailOrIp() {
		quota.reserve(EMAIL, "192.0.2.1");
		for (String key : redis.keys("auth:mail:*")) {
			assertThat(key).doesNotContain(EMAIL, "192.0.2.1");
			assertThat(redis.getExpire(key)).isBetween(1L, 86400L);
		}
	}

	private long concurrentReservations(boolean sameEmail) throws Exception {
		try (var executor = Executors.newFixedThreadPool(16)) {
			var tasks = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 40; i++) {
				int n = i;
				tasks.add(() -> quota.reserve(sameEmail ? EMAIL : "fisher" + n + "@example.com",
						sameEmail ? "192.0.2." + n : "192.0.2.1").allowed());
			}
			long allowed = 0;
			for (var result : executor.invokeAll(tasks)) if (result.get()) allowed++;
			return allowed;
		}
	}
}
