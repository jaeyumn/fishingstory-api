package kr.fishingstory.auth.infrastructure.valkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Component
public class LoginAttemptLimiter {
	private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>();
	static {
		RESERVE.setLocation(new ClassPathResource("valkey/reserve-login-attempt.lua"));
		RESERVE.setResultType(Long.class);
	}
	private final StringRedisTemplate redis;
	public LoginAttemptLimiter(StringRedisTemplate redis) { this.redis = redis; }
	public void reserve(String normalizedEmail, String canonicalIp) {
		if (normalizedEmail == null || normalizedEmail.isBlank() || canonicalIp == null || canonicalIp.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		Long wait = redis.execute(RESERVE, List.of("auth:login:email:" + digest(normalizedEmail),
				"auth:login:ip:" + digest(canonicalIp)), UUID.randomUUID().toString());
		if (wait == null || wait < 0) throw new IllegalStateException("Unable to reserve login attempt");
		if (wait > 0) throw new ApiException(ErrorCode.LOGIN_RATE_LIMITED, (wait + 999) / 1000);
	}
	private static String digest(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception impossible) { throw new IllegalStateException("Unable to hash login limit key"); }
	}
}
