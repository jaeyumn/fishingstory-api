package kr.fishingstory.auth.infrastructure.valkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Component
public class RefreshSessionStore {
	private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>();
	private static final DefaultRedisScript<String> FIND = new DefaultRedisScript<>();
	private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>();
	private static final DefaultRedisScript<Long> LOGOUT = new DefaultRedisScript<>();
	private static final DefaultRedisScript<Long> LOGOUT_ALL = new DefaultRedisScript<>();
	static {
		CREATE.setLocation(new ClassPathResource("valkey/create-refresh-session.lua"));
		CREATE.setResultType(Long.class);
		FIND.setLocation(new ClassPathResource("valkey/find-refresh-session.lua"));
		FIND.setResultType(String.class);
		ROTATE.setLocation(new ClassPathResource("valkey/rotate-refresh-session.lua"));
		ROTATE.setResultType(Long.class);
		LOGOUT.setLocation(new ClassPathResource("valkey/logout-session.lua"));
		LOGOUT.setResultType(Long.class);
		LOGOUT_ALL.setLocation(new ClassPathResource("valkey/logout-all-sessions.lua"));
		LOGOUT_ALL.setResultType(Long.class);
	}
	private final StringRedisTemplate redis;
	public RefreshSessionStore(StringRedisTemplate redis) { this.redis = redis; }

	public Instant create(long userId, String sessionId, String token) {
		if (userId <= 0 || sessionId == null || !sessionId.matches("[0-9a-f-]{36}")
				|| token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid refresh session");
		String hash = digest(token);
		Long expiry = redis.execute(CREATE, List.of("auth:refresh:" + hash, "auth:user:" + userId + ":sessions"),
				Long.toString(userId), sessionId, hash);
		if (expiry == null || expiry < 0) throw new IllegalStateException("Unable to create refresh session");
		return Instant.ofEpochMilli(expiry);
	}
	public Optional<Session> findValid(String token) {
		requireToken(token);
		String result = redis.execute(FIND, List.of("auth:refresh:" + digest(token)));
		if (result == null) return Optional.empty();
		String[] fields = result.split(":", -1);
		if (fields.length != 2) throw new IllegalStateException("Invalid refresh session data");
		return Optional.of(new Session(Long.parseLong(fields[0]), fields[1]));
	}

	public Optional<Instant> rotate(String oldToken, String newToken, Session expected) {
		requireToken(oldToken);
		requireToken(newToken);
		String oldHash = digest(oldToken);
		String newHash = digest(newToken);
		Long expiry = redis.execute(ROTATE, List.of("auth:refresh:" + oldHash, "auth:refresh:" + newHash,
				"auth:user:" + expected.userId() + ":sessions"), Long.toString(expected.userId()), expected.sessionId(), oldHash, newHash);
		if (expiry == null || expiry < 0) throw new IllegalStateException("Unable to rotate refresh session");
		return expiry == 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(expiry));
	}
	public void logout(long userId, String sessionId) {
		if (userId <= 0 || sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Invalid logout session");
		String index = "auth:user:" + userId + ":sessions";
		// 기존 인덱스를 사용한다. 조회 후 Rotation이 일어나면 Lua는 쓰기 없이 재시도를 요구한다.
		for (int attempt = 0; attempt < 3; attempt++) {
			var hashes = redis.opsForZSet().range(index, 0, -1);
			if (hashes == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
			var keys = new ArrayList<String>();
			var args = new ArrayList<String>();
			keys.add(index);
			args.add(Long.toString(userId));
			args.add(sessionId);
			for (String hash : hashes) {
				keys.add("auth:refresh:" + hash);
				args.add(hash);
			}
			Long result = redis.execute(LOGOUT, keys, args.toArray());
			if (Long.valueOf(1).equals(result)) return;
			if (!Long.valueOf(0).equals(result)) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
	}
	public void logoutAll(long userId) {
		if (userId <= 0) throw new IllegalArgumentException("Invalid user session index");
		String index = "auth:user:" + userId + ":sessions";
		for (int attempt = 0; attempt < 3; attempt++) {
			var hashes = redis.opsForZSet().range(index, 0, -1);
			if (hashes == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
			var keys = new ArrayList<String>();
			var args = new ArrayList<String>();
			keys.add(index);
			args.add(Long.toString(userId));
			for (String hash : hashes) {
				keys.add("auth:refresh:" + hash);
				args.add(hash);
			}
			Long result = redis.execute(LOGOUT_ALL, keys, args.toArray());
			if (Long.valueOf(1).equals(result)) return;
			if (!Long.valueOf(0).equals(result)) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
	}
	private static void requireToken(String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid refresh token");
	}
	public record Session(long userId, String sessionId) { }
	static String digest(String token) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception impossible) { throw new IllegalStateException("Unable to hash refresh token"); }
	}
}
