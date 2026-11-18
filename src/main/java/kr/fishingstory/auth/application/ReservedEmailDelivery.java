package kr.fishingstory.auth.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.mail.EmailSender;

/** Internal operation: the caller owns a successful reservation and a stored, live code. */
@Service
public class ReservedEmailDelivery {
	private final EmailSendQuota quota;
	private final EmailSender sender;

	public ReservedEmailDelivery(EmailSendQuota quota, EmailSender sender) {
		this.quota = quota;
		this.sender = sender;
	}

	public EmailSender.Result deliver(EmailSendQuota.Reservation reservation, String normalizedEmail,
			String subject, String text, Instant expiresAt) {
		return deliver(reservation, normalizedEmail, subject, text, expiresAt, () -> true);
	}

	public EmailSender.Result deliver(EmailSendQuota.Reservation reservation, String normalizedEmail,
			String subject, String text, Instant expiresAt, java.util.function.BooleanSupplier stillCurrent) {
		var result = sender.send(new EmailSender.Message(normalizedEmail, subject, text, reservation.id(), expiresAt), stillCurrent);
		if (result.status() == EmailSender.Status.ACCEPTED) {
			if (!quota.confirm(reservation)) throw new IllegalStateException("Accepted email quota confirmation failed");
		} else if (result.status() == EmailSender.Status.REJECTED) {
			quota.release(reservation);
		}
		return result;
	}
}
