package kr.fishingstory.auth.application;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import kr.fishingstory.auth.infrastructure.valkey.EmailSendQuota;
import kr.fishingstory.auth.infrastructure.valkey.SignupEmailCodeStore;
import kr.fishingstory.mail.EmailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SignupEmailCodeDeliveryTests {
	final EmailVerificationCodeGenerator generator = mock(EmailVerificationCodeGenerator.class);
	final SignupEmailCodeStore codes = mock(SignupEmailCodeStore.class);
	final ReservedEmailDelivery delivery = mock(ReservedEmailDelivery.class);
	final SignupEmailCodeDelivery flow = new SignupEmailCodeDelivery(generator, codes, delivery);
	final EmailSendQuota.Reservation reservation = new EmailSendQuota.Reservation("email-key", "operation-id");
	final Instant expiry = Instant.parse("2026-09-13T12:00:00Z");

	@ParameterizedTest
	@EnumSource(EmailSender.Status.class)
	void storesBeforeSendingAndDiscardsOnlyDefiniteRejection(EmailSender.Status status) {
		when(generator.generate()).thenReturn("001234");
		when(codes.replace("fisher@example.com", reservation, "001234")).thenReturn(expiry);
		when(delivery.deliver(eq(reservation), eq("fisher@example.com"), anyString(), anyString(), eq(expiry), any()))
				.thenReturn(new EmailSender.Result(status, null));
		assertThat(flow.send("fisher@example.com", reservation)).isEqualTo(new SignupEmailCodeDelivery.Outcome(status, expiry));
		var order = inOrder(codes, delivery);
		order.verify(codes).replace("fisher@example.com", reservation, "001234");
		order.verify(delivery).deliver(eq(reservation), eq("fisher@example.com"), anyString(), contains("001234"), eq(expiry), any());
		if (status == EmailSender.Status.REJECTED) verify(codes).discard("fisher@example.com", reservation);
		else verify(codes, never()).discard(anyString(), any());
	}
	@Test
	void storageFailureNeverCallsSender() {
		when(generator.generate()).thenReturn("123456");
		when(codes.replace(anyString(), any(), anyString())).thenThrow(new IllegalStateException("Storage unavailable"));
		assertThatThrownBy(() -> flow.send("fisher@example.com", reservation)).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(delivery);
	}
	@Test
	void unexpectedDeliveryFailureDoesNotDiscardPossiblyDeliveredCode() {
		when(generator.generate()).thenReturn("123456");
		when(codes.replace(anyString(), any(), anyString())).thenReturn(expiry);
		when(delivery.deliver(any(), anyString(), anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("Unknown failure"));
		assertThatThrownBy(() -> flow.send("fisher@example.com", reservation)).isInstanceOf(IllegalStateException.class);
		verify(codes, never()).discard(anyString(), any());
	}
	@Test
	void generatorProducesSixAsciiDigits() {
		var actual = new EmailVerificationCodeGenerator();
		for (int i = 0; i < 20; i++) assertThat(actual.generate()).matches("[0-9]{6}");
	}
}
