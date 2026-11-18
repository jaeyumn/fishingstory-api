package kr.fishingstory.auth.application;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.mail.EmailSender;

@Service
public class PasswordResetEmailRequestService {
	private final EmailSendQuota quota;
	private final PasswordResetEmailCodeDelivery delivery;
	private final EmailSender sender;
	private final LoginAccountStore accounts;

	public PasswordResetEmailRequestService(EmailSendQuota quota, PasswordResetEmailCodeDelivery delivery,
			EmailSender sender, LoginAccountStore accounts) {
		this.quota = quota;
		this.delivery = delivery;
		this.sender = sender;
		this.accounts = accounts;
	}

	public Result request(String email, String canonicalIp) {
		sender.validateConfiguration();
		var attempt = quota.reserve(email, canonicalIp);
		if (!attempt.allowed()) throw new Limited(attempt.retryAfter());
		long started = System.nanoTime();
		Instant expiresAt;
		if (accounts.isPasswordResetEligible(email)) {
			expiresAt = delivery.send(email, attempt.reservation()).expiresAt();
		} else {
			if (!quota.confirm(attempt.reservation())) {
				throw new IllegalStateException("Password reset email quota confirmation failed");
			}
			expiresAt = Instant.now().plusSeconds(300);
		}
		long remainingMillis = Math.max(0, 60000 - (System.nanoTime() - started) / 1_000_000);
		return new Result(expiresAt, (remainingMillis + 999) / 1000);
	}

	public record Result(Instant expiresAt, long retryAfterSeconds) { }
	public static class Limited extends ApiException {
		public Limited(Duration retryAfter) {
			super(ErrorCode.EMAIL_RATE_LIMITED, (retryAfter.toMillis() + 999) / 1000);
		}
	}
}
