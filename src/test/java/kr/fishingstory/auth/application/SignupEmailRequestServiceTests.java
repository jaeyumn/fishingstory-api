package kr.fishingstory.auth.application;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.mail.EmailSender;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SignupEmailRequestServiceTests {
	final EmailSendQuota quota = mock(EmailSendQuota.class);
	final SignupEmailCodeDelivery delivery = mock(SignupEmailCodeDelivery.class);
	final EmailSender sender = mock(EmailSender.class);
	final SignupEmailRequestService service = new SignupEmailRequestService(quota, delivery, sender);
	@Test
	void missingConfigurationDoesNotReserveOrSend() {
		doThrow(new IllegalStateException("Not configured")).when(sender).validateConfiguration();
		assertThatThrownBy(() -> service.request("fisher@example.com", "192.0.2.1")).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(quota, delivery);
	}
	@Test
	void rateLimitDoesNotGenerateOrSendCode() {
		when(quota.reserve(anyString(), anyString())).thenReturn(new EmailSendQuota.Attempt(null, Duration.ofSeconds(60)));
		assertThatThrownBy(() -> service.request("fisher@example.com", "192.0.2.1")).isInstanceOf(SignupEmailRequestService.Limited.class);
		verifyNoInteractions(delivery);
	}
	@Test
	void requestReservesAndDeliversExactlyOnce() {
		var reservation = new EmailSendQuota.Reservation("internal", "operation");
		when(quota.reserve(anyString(), anyString())).thenReturn(new EmailSendQuota.Attempt(reservation, Duration.ZERO));
		when(delivery.send("fisher@example.com", reservation)).thenReturn(new SignupEmailCodeDelivery.Outcome(EmailSender.Status.UNKNOWN, Instant.now().plusSeconds(300)));
		assertThat(service.request("fisher@example.com", "192.0.2.1").outcome().status()).isEqualTo(EmailSender.Status.UNKNOWN);
		var order = inOrder(sender, quota, delivery);
		order.verify(sender).validateConfiguration();
		order.verify(quota).reserve("fisher@example.com", "192.0.2.1");
		order.verify(delivery).send("fisher@example.com", reservation);
		verifyNoMoreInteractions(quota, delivery);
	}
}
