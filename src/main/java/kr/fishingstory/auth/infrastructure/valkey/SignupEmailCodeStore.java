package kr.fishingstory.auth.infrastructure.valkey;

import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class SignupEmailCodeStore {
	private static final DefaultRedisScript<Long> STORE = script("store-signup-code.lua");
	private static final DefaultRedisScript<Long> DISCARD = script("discard-signup-code.lua");
	private final StringRedisTemplate redis;

	public SignupEmailCodeStore(StringRedisTemplate redis) { this.redis = redis; }

	public Instant replace(String normalizedEmail, EmailSendQuota.Reservation reservation, String code) {
		String emailKey = checkedEmailKey(normalizedEmail, reservation);
		if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("A six-digit code is required");
		Long expiresAt = redis.execute(STORE, List.of(codeKey(emailKey), emailKey + ":cooldown",
				emailKey + ":reservation:" + reservation.id()), reservation.id(), code);
		if (expiresAt == null || expiresAt < 0) throw new IllegalStateException("Signup code reservation is stale or already used");
		return Instant.ofEpochMilli(expiresAt);
	}

	public boolean discard(String normalizedEmail, EmailSendQuota.Reservation reservation) {
		String emailKey = checkedEmailKey(normalizedEmail, reservation);
		return Long.valueOf(1).equals(redis.execute(DISCARD, List.of(codeKey(emailKey)), reservation.id()));
	}

	public boolean isCurrent(String normalizedEmail, EmailSendQuota.Reservation reservation) {
		String key = codeKey(checkedEmailKey(normalizedEmail, reservation));
		return reservation.id().equals(redis.opsForHash().get(key, "operationId"));
	}

	private String checkedEmailKey(String email, EmailSendQuota.Reservation reservation) {
		if (email == null || email.isBlank() || reservation == null
				|| !EmailSendQuota.emailKey(email).equals(reservation.emailKey())) {
			throw new IllegalArgumentException("Email does not match reservation");
		}
		return reservation.emailKey();
	}
	static String codeKey(String emailKey) { return emailKey + ":signup-code"; }
	private static DefaultRedisScript<Long> script(String file) {
		var script = new DefaultRedisScript<Long>();
		script.setLocation(new ClassPathResource("valkey/" + file));
		script.setResultType(Long.class);
		return script;
	}
}
