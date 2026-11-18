package kr.fishingstory.auth.infrastructure.valkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Component
public class WithdrawalCancellationVerificationStore {
	private static final DefaultRedisScript<Long> VERIFY = script("verify-signup-code.lua");
	private final StringRedisTemplate redis;

	public WithdrawalCancellationVerificationStore(StringRedisTemplate redis) { this.redis = redis; }

	public Instant verify(String email, String code, String token) {
		if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Six-digit code required");
		Long result = redis.execute(VERIFY, List.of(
				WithdrawalCancellationEmailCodeStore.codeKey(EmailSendQuota.emailKey(email)), grantKey(email, token)), code);
		if (result == null || result == -4) throw new IllegalStateException("Unable to create withdrawal cancellation verification");
		if (result == -1 || result == -2 || result == -3) {
			throw new Rejected(ErrorCode.RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED);
		}
		if (result < 0) throw new IllegalStateException("Unexpected verification result");
		return Instant.ofEpochMilli(result);
	}

	public boolean consume(String email, String token) {
		return redis.opsForValue().getAndDelete(grantKey(email, token)) != null;
	}

	static String grantKey(String email, String token) {
		if (email == null || email.isBlank() || token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
			throw new IllegalArgumentException("Email and withdrawal cancellation token required");
		}
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
			return EmailSendQuota.emailKey(email) + ":withdrawal-cancel-grant:" + hash;
		} catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	private static DefaultRedisScript<Long> script(String file) {
		var script = new DefaultRedisScript<Long>();
		script.setLocation(new ClassPathResource("valkey/" + file));
		script.setResultType(Long.class);
		return script;
	}
	public static class Rejected extends ApiException {
		public Rejected(ErrorCode code) { super(code); }
	}
}
