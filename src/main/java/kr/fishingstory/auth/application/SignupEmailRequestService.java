package kr.fishingstory.auth.application;

import kr.fishingstory.common.error.ErrorCode;

import kr.fishingstory.common.error.ApiException;

import java.time.Duration;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.mail.EmailSender;

@Service
public class SignupEmailRequestService {
	private final EmailSendQuota quota;
	private final SignupEmailCodeDelivery delivery;
	private final EmailSender sender;

	public SignupEmailRequestService(EmailSendQuota quota, SignupEmailCodeDelivery delivery, EmailSender sender) {
		this.quota = quota;
		this.delivery = delivery;
		this.sender = sender;
	}

	public Result request(String normalizedEmail, String canonicalIp) {
		sender.validateConfiguration();
		var attempt = quota.reserve(normalizedEmail, canonicalIp);
		if (!attempt.allowed()) throw new Limited(attempt.retryAfter());
		long started = System.nanoTime();
		var outcome = delivery.send(normalizedEmail, attempt.reservation());
		long remainingMillis = Math.max(0, 60000 - (System.nanoTime() - started) / 1_000_000);
		return new Result(outcome, (remainingMillis + 999) / 1000);
	}
	public record Result(SignupEmailCodeDelivery.Outcome outcome, long retryAfterSeconds) { }
	public static class Limited extends ApiException {
		public Limited(Duration retryAfter) {
			super(ErrorCode.EMAIL_RATE_LIMITED, (retryAfter.toMillis() + 999) / 1000);
		}
	}
}
