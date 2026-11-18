package kr.fishingstory.auth.infrastructure.valkey;

import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

/** 회원별 최근 15분 비밀번호 변경 시도를 성공/실패와 관계없이 5회로 제한한다. */
@Component
public class PasswordChangeAttemptLimiter {
	private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>();
	static {
		RESERVE.setLocation(new ClassPathResource("valkey/reserve-password-change-attempt.lua"));
		RESERVE.setResultType(Long.class);
	}
	private final StringRedisTemplate redis;
	public PasswordChangeAttemptLimiter(StringRedisTemplate redis) { this.redis = redis; }
	public void reserve(long userId) {
		if (userId <= 0) throw new ApiException(ErrorCode.ACCESS_DENIED);
		Long wait = redis.execute(RESERVE, List.of("auth:password-change:user:" + userId), UUID.randomUUID().toString());
		if (wait == null || wait < 0) throw new IllegalStateException("Unable to reserve password change attempt");
		if (wait > 0) throw new ApiException(ErrorCode.PASSWORD_CHANGE_RATE_LIMITED, (wait + 999) / 1000);
	}
}
