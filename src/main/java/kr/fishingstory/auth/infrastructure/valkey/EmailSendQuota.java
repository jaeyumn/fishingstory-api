package kr.fishingstory.auth.infrastructure.valkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Shared by signup, password reset and withdrawal cancellation. Inputs must already be normalized and trusted. */
@Component
public class EmailSendQuota {
	private static final DefaultRedisScript<Long> RESERVE = script("reserve-email.lua");
	private static final DefaultRedisScript<Long> COMPLETE = script("complete-email.lua");
	private final StringRedisTemplate redis;

	public EmailSendQuota(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** Call once per external request, never again for provider retries. */
	public Attempt reserve(String normalizedEmail, String canonicalClientIp) {
		if (normalizedEmail == null || normalizedEmail.isBlank() || canonicalClientIp == null || canonicalClientIp.isBlank()) {
			throw new IllegalArgumentException("Normalized email and canonical client IP are required");
		}
		String id = UUID.randomUUID().toString();
		String emailKey = emailKey(normalizedEmail);
		Long wait = redis.execute(RESERVE,
				List.of("auth:mail:ip:" + digest(canonicalClientIp), emailKey, emailKey + ":cooldown", reservationKey(emailKey, id)), id);
		if (wait == null) throw new IllegalStateException("Missing quota result");
		return new Attempt(wait == 0 ? new Reservation(emailKey, id) : null, Duration.ofMillis(wait));
	}

	public boolean confirm(Reservation reservation) {
		return complete(reservation, "CONFIRM");
	}

	/** Only for a confirmed pre-acceptance failure. Ambiguous outcomes remain pending. */
	public boolean release(Reservation reservation) {
		return complete(reservation, "RELEASE");
	}

	private boolean complete(Reservation reservation, String action) {
		return Long.valueOf(1).equals(redis.execute(COMPLETE,
				List.of(reservation.emailKey(), reservationKey(reservation.emailKey(), reservation.id())), reservation.id(), action));
	}

	static String emailKey(String email) { return "auth:mail:email:" + digest(email); }
	private static String reservationKey(String emailKey, String id) { return emailKey + ":reservation:" + id; }
	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
	private static DefaultRedisScript<Long> script(String name) {
		var script = new DefaultRedisScript<Long>();
		script.setLocation(new ClassPathResource("valkey/" + name));
		script.setResultType(Long.class);
		return script;
	}
	public record Reservation(String emailKey, String id) { }
	public record Attempt(Reservation reservation, Duration retryAfter) {
		public boolean allowed() { return reservation != null; }
	}
}
