package kr.fishingstory.auth.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.auth.infrastructure.valkey.SignupEmailCodeStore;
import kr.fishingstory.mail.EmailSender;

/** Internal flow only: caller supplies a successful quota reservation, once per request. */
@Service
public class SignupEmailCodeDelivery {
	private final EmailVerificationCodeGenerator generator;
	private final SignupEmailCodeStore codes;
	private final ReservedEmailDelivery delivery;

	public SignupEmailCodeDelivery(EmailVerificationCodeGenerator generator, SignupEmailCodeStore codes,
			ReservedEmailDelivery delivery) {
		this.generator = generator;
		this.codes = codes;
		this.delivery = delivery;
	}

	public Outcome send(String normalizedEmail, EmailSendQuota.Reservation reservation) {
		String code = generator.generate();
		Instant expiresAt = codes.replace(normalizedEmail, reservation, code);
		var result = delivery.deliver(reservation, normalizedEmail, "낚시이야기 회원가입 이메일 인증",
				"인증코드는 " + code + "입니다.\n코드 발급 후 5분 안에 입력해주세요.\n재전송했다면 가장 최근 코드를 사용해주세요.", expiresAt,
				() -> codes.isCurrent(normalizedEmail, reservation));
		if (result.status() == EmailSender.Status.REJECTED) codes.discard(normalizedEmail, reservation);
		return new Outcome(result.status(), expiresAt);
	}

	public record Outcome(EmailSender.Status status, Instant expiresAt) { }
}
