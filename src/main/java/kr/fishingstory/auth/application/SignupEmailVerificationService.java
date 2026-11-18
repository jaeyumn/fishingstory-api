package kr.fishingstory.auth.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;

@Service
public class SignupEmailVerificationService {
	private final SignupVerificationStore store;
	private final SecureRandom random = new SecureRandom();
	public SignupEmailVerificationService(SignupVerificationStore store) { this.store = store; }

	public Grant verify(String normalizedEmail, String code) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = store.verify(normalizedEmail, code, token);
		return new Grant(token, expiresAt);
	}
	public record Grant(String signupToken, Instant expiresAt) {
		@Override public String toString() { return "Grant[signupToken=REDACTED, expiresAt=" + expiresAt + "]"; }
	}
}
