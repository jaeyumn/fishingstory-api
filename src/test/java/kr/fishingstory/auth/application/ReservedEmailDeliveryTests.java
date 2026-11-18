package kr.fishingstory.auth.application;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.mail.EmailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReservedEmailDeliveryTests {
	final EmailSendQuota quota = mock(EmailSendQuota.class);
	final EmailSender sender = mock(EmailSender.class);
	final ReservedEmailDelivery delivery = new ReservedEmailDelivery(quota, sender);
	final EmailSendQuota.Reservation reservation = new EmailSendQuota.Reservation("internal-key", "operation-123");

	@Test
	void confirmsAcceptedMessageWithSameOperationId() {
		Instant expiry = Instant.now().plusSeconds(300);
		when(sender.send(any(), any())).thenReturn(new EmailSender.Result(EmailSender.Status.ACCEPTED, "email-id"));
		when(quota.confirm(reservation)).thenReturn(true);
		assertThat(delivery.deliver(reservation, "fisher@example.com", "제목", "본문", expiry).providerId()).isEqualTo("email-id");
		verify(sender).send(org.mockito.ArgumentMatchers.eq(new EmailSender.Message("fisher@example.com", "제목", "본문", "operation-123", expiry)), any());
		verify(quota).confirm(reservation);
		verifyNoMoreInteractions(quota);
	}

	@Test
	void releasesOnlyDefiniteRejection() {
		when(sender.send(any(), any())).thenReturn(new EmailSender.Result(EmailSender.Status.REJECTED, null));
		delivery.deliver(reservation, "fisher@example.com", "제목", "본문", Instant.now().plusSeconds(300));
		verify(quota).release(reservation);
		verifyNoMoreInteractions(quota);
	}

	@Test
	void retainsUnknownOutcomeWithoutChangingQuota() {
		when(sender.send(any(), any())).thenReturn(new EmailSender.Result(EmailSender.Status.UNKNOWN, null));
		delivery.deliver(reservation, "fisher@example.com", "제목", "본문", Instant.now().plusSeconds(300));
		verifyNoInteractions(quota);
	}

	@Test
	void failedConfirmationNeverResendsOrReleasesAcceptedMessage() {
		when(sender.send(any(), any())).thenReturn(new EmailSender.Result(EmailSender.Status.ACCEPTED, "email-id"));
		when(quota.confirm(reservation)).thenReturn(false);
		assertThatThrownBy(() -> delivery.deliver(reservation, "fisher@example.com", "제목", "본문", Instant.now().plusSeconds(300)))
				.isInstanceOf(IllegalStateException.class);
		verify(sender).send(any(), any());
		verify(quota).confirm(reservation);
		verifyNoMoreInteractions(quota, sender);
	}
}
