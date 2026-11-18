package kr.fishingstory.auth.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.auth.infrastructure.valkey.WithdrawalCancellationEmailCodeStore;
import kr.fishingstory.mail.EmailSender;

@Service
public class WithdrawalCancellationEmailCodeDelivery {
	private final EmailVerificationCodeGenerator generator;
	private final WithdrawalCancellationEmailCodeStore codes;
	private final EmailSendQuota quota;
	private final EmailSender sender;

	public WithdrawalCancellationEmailCodeDelivery(EmailVerificationCodeGenerator generator,
			WithdrawalCancellationEmailCodeStore codes, EmailSendQuota quota, EmailSender sender) {
		this.generator = generator;
		this.codes = codes;
		this.quota = quota;
		this.sender = sender;
	}

	public Outcome send(String email, EmailSendQuota.Reservation reservation) {
		String code = generator.generate();
		Instant expiresAt = codes.replace(email, reservation, code);
		var result = sender.send(new EmailSender.Message(email, "낚시이야기 회원 탈퇴 취소 인증",
				"인증코드는 " + code + "입니다.\n코드 발급 후 5분 안에 입력해주세요.\n요청하지 않았다면 이 메일을 무시해주세요.",
				reservation.id(), expiresAt), () -> codes.isCurrent(email, reservation));
		if (result.status() == EmailSender.Status.REJECTED) codes.discard(email, reservation);
		if (!quota.confirm(reservation)) throw new IllegalStateException("Withdrawal cancellation email quota confirmation failed");
		return new Outcome(result.status(), expiresAt);
	}

	public record Outcome(EmailSender.Status status, Instant expiresAt) { }
}
