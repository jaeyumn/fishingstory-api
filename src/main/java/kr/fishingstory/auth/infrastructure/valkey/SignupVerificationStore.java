package kr.fishingstory.auth.infrastructure.valkey;

import kr.fishingstory.common.error.ErrorCode;

import kr.fishingstory.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class SignupVerificationStore {
	private final StringRedisTemplate redis;
	private static final DefaultRedisScript<Long> VERIFY;
	private static final DefaultRedisScript<Long> FIND_VALID;
	static {
		VERIFY = new DefaultRedisScript<>();
		VERIFY.setLocation(new ClassPathResource("valkey/verify-signup-code.lua"));
		VERIFY.setResultType(Long.class);
		FIND_VALID = new DefaultRedisScript<>();
		FIND_VALID.setLocation(new ClassPathResource("valkey/find-valid-signup-grant.lua"));
		FIND_VALID.setResultType(Long.class);
	}
	public SignupVerificationStore(StringRedisTemplate redis) { this.redis = redis; }

	public Instant verify(String normalizedEmail, String code, String token) {
		if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Six-digit code required");
		Long result = redis.execute(VERIFY, List.of(SignupEmailCodeStore.codeKey(EmailSendQuota.emailKey(normalizedEmail)),
				grantKey(normalizedEmail, token)), code);
		if (result == null || result == -4) throw new IllegalStateException("Unable to create signup verification");
		if (result == -1) throw new Rejected(ErrorCode.EMAIL_CODE_INVALID_OR_EXPIRED);
		if (result == -2) throw new Rejected(ErrorCode.EMAIL_CODE_MISMATCH);
		if (result == -3) throw new Rejected(ErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
		if (result < 0) throw new IllegalStateException("Unexpected verification result");
		return Instant.ofEpochMilli(result);
	}

	/** 조회 시점의 유효한 인증 만료시각을 반환한다. 토큰을 삭제하거나 TTL을 연장하지 않으며 가입을 예약하지 않는다. */
	public Optional<Instant> findValid(String normalizedEmail, String token) {
		Long result = redis.execute(FIND_VALID, List.of(grantKey(normalizedEmail, token)));
		if (result == null) throw new IllegalStateException("Unable to read signup verification");
		if (result == -1) return Optional.empty();
		if (result < 0) throw new IllegalStateException("Unexpected signup verification result");
		return Optional.of(Instant.ofEpochMilli(result));
	}

	/** DB 커밋 후 정리용이다. 회원 저장 전에 호출하지 않으며, 중복 가입은 EMAIL 계정의 이메일 UNIQUE로 막는다. */
	public boolean consume(String normalizedEmail, String token) {
		return redis.opsForValue().getAndDelete(grantKey(normalizedEmail, token)) != null;
	}

	static String grantKey(String email, String token) {
		if (email == null || email.isBlank() || token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
			throw new IllegalArgumentException("Email and signup token required");
		}
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
			return EmailSendQuota.emailKey(email) + ":signup-grant:" + hash;
		} catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	public static class Rejected extends ApiException {
		public Rejected(ErrorCode code) { super(code); }
	}
}
