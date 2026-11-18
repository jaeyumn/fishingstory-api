package kr.fishingstory.auth.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.WithdrawalCancellationVerificationStore;

@Service
public class WithdrawalCancellationEmailVerificationService {
	private final WithdrawalCancellationVerificationStore store;
	private final SecureRandom random = new SecureRandom();

	public WithdrawalCancellationEmailVerificationService(WithdrawalCancellationVerificationStore store) {
		this.store = store;
	}

	public Grant verify(String email, String code) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new Grant(token, store.verify(email, code, token));
	}

	public record Grant(String cancellationToken, Instant expiresAt) {
		@Override public String toString() { return "Grant[cancellationToken=REDACTED, expiresAt=" + expiresAt + "]"; }
	}
}
