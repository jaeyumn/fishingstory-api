package kr.fishingstory.auth.infrastructure.valkey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginSessionStoreTests {
	static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	static LoginAttemptLimiter limiter;
	static RefreshSessionStore sessions;
	@BeforeAll
	static void start() {
		VALKEY.start();
		connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		limiter = new LoginAttemptLimiter(redis);
		sessions = new RefreshSessionStore(redis);
	}
	@AfterAll
	static void stop() {
		if (connection != null) connection.destroy();
		VALKEY.stop();
	}
	@BeforeEach
	void clear() { try (var client = connection.getConnection()) { client.serverCommands().flushDb(); } }
	@Test
	void concurrentAttemptsCannotExceedEmailLimit() throws Exception {
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 30; i++) tasks.add(() -> {
				try { limiter.reserve("fisher@example.com", "192.0.2.1"); return true; }
				catch (ApiException failure) {
					assertThat(failure.errorCode()).isEqualTo(ErrorCode.LOGIN_RATE_LIMITED);
					assertThat(failure.retryAfterSeconds()).isBetween(890L, 900L);
					return false;
				}
			});
			int successes = 0;
			for (var result : executor.invokeAll(tasks)) if (result.get()) successes++;
			assertThat(successes).isEqualTo(10);
		}
		assertThat(redis.keys("auth:login:*")).allSatisfy(key -> assertThat(key).doesNotContain("fisher@example.com", "192.0.2.1"));
	}
	@Test
	void ipLimitAppliesAcrossDifferentEmails() {
		for (int i = 0; i < 50; i++) limiter.reserve("fisher" + i + "@example.com", "192.0.2.1");
		assertThatThrownBy(() -> limiter.reserve("another@example.com", "192.0.2.1"))
				.isInstanceOf(ApiException.class).hasMessage("LOGIN_RATE_LIMITED");
		assertThatCode(() -> limiter.reserve("another@example.com", "192.0.2.2")).doesNotThrowAnyException();
	}
	@Test
	void blockedAttemptDoesNotExtendWindowAndOldRecordsStopCounting() {
		for (int i = 0; i < 10; i++) limiter.reserve("fisher@example.com", "192.0.2.1");
		for (String key : redis.keys("auth:login:*")) redis.expire(key, Duration.ofSeconds(100));
		assertThatThrownBy(() -> limiter.reserve("fisher@example.com", "192.0.2.1")).isInstanceOf(ApiException.class);
		for (String key : redis.keys("auth:login:*")) {
			assertThat(redis.getExpire(key)).isBetween(90L, 100L);
			for (String member : redis.opsForZSet().range(key, 0, -1)) redis.opsForZSet().add(key, member, 1);
		}
		assertThatCode(() -> limiter.reserve("fisher@example.com", "192.0.2.1")).doesNotThrowAnyException();
		for (String key : redis.keys("auth:login:*")) assertThat(redis.opsForZSet().zCard(key)).isEqualTo(1L);
	}
	@Test
	void refreshCollisionDoesNotOverwriteOwnerOrExtendExpiry() {
		String token = "A".repeat(43);
		String sessionId = UUID.randomUUID().toString();
		sessions.create(123, sessionId, token);
		String key = "auth:refresh:" + RefreshSessionStore.digest(token);
		redis.expire(key, Duration.ofSeconds(100));
		assertThatThrownBy(() -> sessions.create(456, UUID.randomUUID().toString(), token)).isInstanceOf(IllegalStateException.class);
		assertThat(redis.opsForHash().get(key, "userId")).isEqualTo("123");
		assertThat(redis.opsForHash().get(key, "sessionId")).isEqualTo(sessionId);
		assertThat(redis.getExpire(key)).isBetween(90L, 100L);
		assertThat(redis.hasKey("auth:user:456:sessions")).isFalse();
	}
	@Test
	void newLoginPreservesOtherSessionsAndPrunesExpiredIndexMembers() {
		sessions.create(123, UUID.randomUUID().toString(), "A".repeat(43));
		String index = "auth:user:123:sessions";
		redis.opsForZSet().add(index, "expired-token-hash", 1);
		sessions.create(123, UUID.randomUUID().toString(), "B".repeat(43));
		assertThat(redis.keys("auth:refresh:*")).hasSize(2);
		assertThat(redis.opsForZSet().range(index, 0, -1)).hasSize(2).doesNotContain("expired-token-hash");
		assertThat(redis.getExpire(index)).isBetween(2591990L, 2592000L);
	}
	@Test
	void rotationPreservesSessionAndCreationTimeRenewsExpiryAndKeepsOtherDevices() {
		String old = "A".repeat(43), next = "B".repeat(43), other = "C".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		sessions.create(123, UUID.randomUUID().toString(), other);
		String oldKey = "auth:refresh:" + RefreshSessionStore.digest(old);
		String nextKey = "auth:refresh:" + RefreshSessionStore.digest(next);
		Object createdAt = redis.opsForHash().get(oldKey, "createdAt");
		redis.expire(oldKey, Duration.ofSeconds(100));
		var session = sessions.findValid(old).orElseThrow();
		assertThat(redis.getExpire(oldKey)).isBetween(90L, 100L);
		var expiry = sessions.rotate(old, next, session).orElseThrow();
		assertThat(sessions.findValid(old)).isEmpty();
		assertThat(sessions.findValid(next)).contains(session);
		assertThat(sessions.findValid(other)).isPresent();
		assertThat(redis.opsForHash().get(nextKey, "createdAt")).isEqualTo(createdAt);
		assertThat(redis.opsForHash().get(nextKey, "expiresAt")).isEqualTo(Long.toString(expiry.toEpochMilli()));
		assertThat(redis.getExpire(nextKey)).isBetween(2591990L, 2592000L);
		assertThat(redis.opsForZSet().range("auth:user:123:sessions", 0, -1))
				.containsExactlyInAnyOrder(RefreshSessionStore.digest(next), RefreshSessionStore.digest(other));
		assertThat(sessions.rotate(old, "D".repeat(43), session)).isEmpty();
	}
	@Test
	void concurrentRotationHasExactlyOneWinner() throws Exception {
		String old = "A".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 20; i++) {
				String next = String.valueOf((char) ('B' + i)).repeat(43);
				tasks.add(() -> sessions.rotate(old, next, session).isPresent());
			}
			int successes = 0;
			for (var result : executor.invokeAll(tasks)) if (result.get()) successes++;
			assertThat(successes).isEqualTo(1);
		}
		assertThat(redis.keys("auth:refresh:*")).hasSize(1);
		assertThat(redis.opsForZSet().zCard("auth:user:123:sessions")).isEqualTo(1L);
	}
	@Test
	void collisionAndMismatchedOwnerDoNotConsumeOldToken() {
		String old = "A".repeat(43), next = "B".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		sessions.create(456, UUID.randomUUID().toString(), next);
		var session = sessions.findValid(old).orElseThrow();
		assertThatThrownBy(() -> sessions.rotate(old, next, session)).isInstanceOf(IllegalStateException.class);
		assertThat(sessions.rotate(old, "C".repeat(43), new RefreshSessionStore.Session(456, session.sessionId()))).isEmpty();
		assertThat(sessions.findValid(old)).contains(session);
		assertThat(sessions.findValid(next).orElseThrow().userId()).isEqualTo(456);
	}
	@Test
	void expiredOrMissingTokenCannotBeReadOrRotated() {
		String old = "A".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		String key = "auth:refresh:" + RefreshSessionStore.digest(old);
		redis.opsForHash().put(key, "expiresAt", "1");
		assertThat(sessions.findValid(old)).isEmpty();
		assertThat(sessions.rotate(old, "B".repeat(43), session)).isEmpty();
		redis.delete(key);
		assertThat(sessions.findValid(old)).isEmpty();
		assertThat(sessions.rotate(old, "B".repeat(43), session)).isEmpty();
	}
	@Test
	void logoutRemovesOnlyMatchingUserAndSessionAndIsRepeatable() {
		String sessionId = UUID.randomUUID().toString();
		String current = "A".repeat(43), otherDevice = "B".repeat(43), otherUser = "C".repeat(43);
		sessions.create(123, sessionId, current);
		sessions.create(123, UUID.randomUUID().toString(), otherDevice);
		sessions.create(456, sessionId, otherUser);
		String index = "auth:user:123:sessions";
		redis.expire(index, Duration.ofSeconds(100));
		sessions.logout(123, sessionId);
		sessions.logout(123, sessionId);
		assertThat(sessions.findValid(current)).isEmpty();
		assertThat(sessions.findValid(otherDevice)).isPresent();
		assertThat(sessions.findValid(otherUser)).isPresent();
		assertThat(redis.opsForZSet().range(index, 0, -1)).containsExactly(RefreshSessionStore.digest(otherDevice));
		assertThat(redis.getExpire(index)).isBetween(90L, 100L);
	}
	@Test
	void logoutBeforeRotationPreventsSessionResurrectionAndRemovesEmptyIndex() {
		String old = "A".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		sessions.logout(123, session.sessionId());
		assertThat(sessions.rotate(old, "B".repeat(43), session)).isEmpty();
		assertThat(redis.hasKey("auth:user:123:sessions")).isFalse();
		sessions.logout(123, session.sessionId());
		assertThat(redis.keys("auth:refresh:*")).isEmpty();
	}
	@Test
	void logoutRetriesWhenRotationHappensAfterIndexRead() {
		String old = "A".repeat(43), next = "B".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		var template = spy(redis);
		var indexOps = spy(redis.opsForZSet());
		doReturn(indexOps).when(template).opsForZSet();
		var first = new java.util.concurrent.atomic.AtomicBoolean(true);
		doAnswer(invocation -> {
			var snapshot = redis.opsForZSet().range("auth:user:123:sessions", 0, -1);
			if (first.getAndSet(false)) assertThat(sessions.rotate(old, next, session)).isPresent();
			return snapshot;
		}).when(indexOps).range("auth:user:123:sessions", 0, -1);
		new RefreshSessionStore(template).logout(123, session.sessionId());
		verify(indexOps, times(2)).range("auth:user:123:sessions", 0, -1);
		assertThat(sessions.findValid(old)).isEmpty();
		assertThat(sessions.findValid(next)).isEmpty();
		assertThat(redis.hasKey("auth:user:123:sessions")).isFalse();
	}
	@Test
	void continuousIndexChangesReturnUnavailableWithoutPretendingLogoutSucceeded() {
		String current = "A".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), current);
		var session = sessions.findValid(current).orElseThrow();
		var template = spy(redis);
		var indexOps = spy(redis.opsForZSet());
		doReturn(indexOps).when(template).opsForZSet();
		var changes = new java.util.concurrent.atomic.AtomicInteger();
		doAnswer(invocation -> {
			var snapshot = redis.opsForZSet().range("auth:user:123:sessions", 0, -1);
			String token = String.valueOf((char) ('B' + changes.getAndIncrement())).repeat(43);
			sessions.create(123, UUID.randomUUID().toString(), token);
			return snapshot;
		}).when(indexOps).range("auth:user:123:sessions", 0, -1);
		assertThatThrownBy(() -> new RefreshSessionStore(template).logout(123, session.sessionId()))
				.isInstanceOf(ApiException.class).hasMessage("SERVICE_UNAVAILABLE");
		assertThat(changes.get()).isEqualTo(3);
		assertThat(sessions.findValid(current)).contains(session);
	}
	@Test
	void concurrentRotationAndLogoutLeaveNoRefreshSession() throws Exception {
		String old = "A".repeat(43), next = "B".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		var barrier = new java.util.concurrent.CyclicBarrier(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var rotate = executor.submit(() -> { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); return sessions.rotate(old, next, session); });
			var logout = executor.submit(() -> { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); sessions.logout(123, session.sessionId()); return true; });
			rotate.get(20, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(logout.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		}
		assertThat(sessions.findValid(old)).isEmpty();
		assertThat(sessions.findValid(next)).isEmpty();
		assertThat(redis.hasKey("auth:user:123:sessions")).isFalse();
	}
	@Test
	void logoutAllRemovesEverySessionForOnlyThatUserAndIsRepeatable() {
		String first = "A".repeat(43), second = "B".repeat(43), other = "C".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), first);
		sessions.create(123, UUID.randomUUID().toString(), second);
		sessions.create(456, UUID.randomUUID().toString(), other);
		sessions.logoutAll(123);
		sessions.logoutAll(123);
		assertThat(sessions.findValid(first)).isEmpty();
		assertThat(sessions.findValid(second)).isEmpty();
		assertThat(sessions.findValid(other)).isPresent();
		assertThat(redis.hasKey("auth:user:123:sessions")).isFalse();
	}
	@Test
	void logoutAllRetriesAcrossRotationAndLeavesNoSession() {
		String old = "A".repeat(43), next = "B".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		var template = spy(redis);
		var indexOps = spy(redis.opsForZSet());
		doReturn(indexOps).when(template).opsForZSet();
		var first = new java.util.concurrent.atomic.AtomicBoolean(true);
		doAnswer(invocation -> {
			var snapshot = redis.opsForZSet().range("auth:user:123:sessions", 0, -1);
			if (first.getAndSet(false)) assertThat(sessions.rotate(old, next, session)).isPresent();
			return snapshot;
		}).when(indexOps).range("auth:user:123:sessions", 0, -1);
		new RefreshSessionStore(template).logoutAll(123);
		verify(indexOps, times(2)).range("auth:user:123:sessions", 0, -1);
		assertThat(sessions.findValid(old)).isEmpty();
		assertThat(sessions.findValid(next)).isEmpty();
		assertThat(redis.hasKey("auth:user:123:sessions")).isFalse();
	}
	@Test
	void logoutAllNeverDeletesARefreshKeyOwnedByAnotherUser() {
		String own = "A".repeat(43), foreign = "B".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), own);
		sessions.create(456, UUID.randomUUID().toString(), foreign);
		redis.opsForZSet().add("auth:user:123:sessions", RefreshSessionStore.digest(foreign), Double.MAX_VALUE);
		assertThatThrownBy(() -> sessions.logoutAll(123))
				.isInstanceOf(ApiException.class).hasMessage("SERVICE_UNAVAILABLE");
		assertThat(sessions.findValid(own)).isPresent();
		assertThat(sessions.findValid(foreign)).isPresent();
	}
	@Test
	void logoutPreflightsCorruptKeyTypesBeforeAnyDeletion() {
		String token = "A".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), token);
		var session = sessions.findValid(token).orElseThrow();
		String brokenHash = "b".repeat(64);
		redis.opsForZSet().add("auth:user:123:sessions", brokenHash, Double.MAX_VALUE);
		redis.opsForValue().set("auth:refresh:" + brokenHash, "wrong-type");
		assertThatThrownBy(() -> sessions.logout(123, session.sessionId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(sessions.findValid(token)).contains(session);
	}
	@Test
	void expiredRefreshKeysArePrunedAndForeignOwnerIsNeverDeleted() {
		String token = "A".repeat(43), foreign = "B".repeat(43);
		String sid = UUID.randomUUID().toString();
		sessions.create(123, sid, token);
		sessions.create(456, sid, foreign);
		redis.delete("auth:refresh:" + RefreshSessionStore.digest(token));
		redis.opsForZSet().add("auth:user:123:sessions", RefreshSessionStore.digest(foreign), Double.MAX_VALUE);
		sessions.logout(123, sid);
		assertThat(sessions.findValid(foreign)).isPresent();
		assertThat(redis.opsForZSet().range("auth:user:123:sessions", 0, -1)).containsExactly(RefreshSessionStore.digest(foreign));
	}
	@Test
	void invalidIndexTypeFailsBeforeWritingNewTokenOrDeletingOldToken() {
		String old = "A".repeat(43), next = "B".repeat(43);
		sessions.create(123, UUID.randomUUID().toString(), old);
		var session = sessions.findValid(old).orElseThrow();
		redis.delete("auth:user:123:sessions");
		redis.opsForValue().set("auth:user:123:sessions", "invalid-index");
		assertThatThrownBy(() -> sessions.rotate(old, next, session)).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(sessions.findValid(old)).contains(session);
		assertThat(sessions.findValid(next)).isEmpty();
	}
}
