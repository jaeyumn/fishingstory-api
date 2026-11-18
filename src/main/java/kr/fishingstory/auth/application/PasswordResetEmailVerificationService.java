package kr.fishingstory.auth.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.PasswordResetVerificationStore;

@Service
public class PasswordResetEmailVerificationService {
	private final PasswordResetVerificationStore store;
	private final SecureRandom random = new SecureRandom();

	public PasswordResetEmailVerificationService(PasswordResetVerificationStore store) { this.store = store; }

	public Grant verify(String email, String code) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new Grant(token, store.verify(email, code, token));
	}

	public record Grant(String resetToken, Instant expiresAt) {
		@Override public String toString() { return "Grant[resetToken=REDACTED, expiresAt=" + expiresAt + "]"; }
	}
}
