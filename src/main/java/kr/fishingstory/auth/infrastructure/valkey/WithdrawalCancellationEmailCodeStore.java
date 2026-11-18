package kr.fishingstory.auth.infrastructure.valkey;

import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalCancellationEmailCodeStore {
	private static final DefaultRedisScript<Long> STORE = script("store-signup-code.lua");
	private static final DefaultRedisScript<Long> DISCARD = script("discard-signup-code.lua");
	private final StringRedisTemplate redis;

	public WithdrawalCancellationEmailCodeStore(StringRedisTemplate redis) { this.redis = redis; }

	public Instant replace(String email, EmailSendQuota.Reservation reservation, String code) {
		String emailKey = checkedEmailKey(email, reservation);
		if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("A six-digit code is required");
		Long expiresAt = redis.execute(STORE, List.of(codeKey(emailKey), emailKey + ":cooldown",
				emailKey + ":reservation:" + reservation.id()), reservation.id(), code);
		if (expiresAt == null || expiresAt < 0) throw new IllegalStateException("Withdrawal cancellation code reservation is stale or already used");
		return Instant.ofEpochMilli(expiresAt);
	}

	public boolean discard(String email, EmailSendQuota.Reservation reservation) {
		String emailKey = checkedEmailKey(email, reservation);
		return Long.valueOf(1).equals(redis.execute(DISCARD, List.of(codeKey(emailKey)), reservation.id()));
	}

	public boolean isCurrent(String email, EmailSendQuota.Reservation reservation) {
		return reservation.id().equals(redis.opsForHash().get(codeKey(checkedEmailKey(email, reservation)), "operationId"));
	}

	private String checkedEmailKey(String email, EmailSendQuota.Reservation reservation) {
		if (email == null || email.isBlank() || reservation == null
				|| !EmailSendQuota.emailKey(email).equals(reservation.emailKey())) {
			throw new IllegalArgumentException("Email does not match reservation");
		}
		return reservation.emailKey();
	}
	static String codeKey(String emailKey) { return emailKey + ":withdrawal-cancel-code"; }
	private static DefaultRedisScript<Long> script(String file) {
		var script = new DefaultRedisScript<Long>();
		script.setLocation(new ClassPathResource("valkey/" + file));
		script.setResultType(Long.class);
		return script;
	}
}
