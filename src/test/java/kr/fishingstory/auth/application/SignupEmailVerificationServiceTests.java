package kr.fishingstory.auth.application;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class SignupEmailVerificationServiceTests {
	@Test
	void creates256BitTokenAndUsesStoreExpiryWithoutLoggingToken() {
		var store = mock(SignupVerificationStore.class);
		Instant expiry = Instant.parse("2026-09-13T13:00:00Z");
		when(store.verify(eq("fisher@example.com"), eq("001234"), anyString())).thenReturn(expiry);
		var grant = new SignupEmailVerificationService(store).verify("fisher@example.com", "001234");
		assertThat(Base64.getUrlDecoder().decode(grant.signupToken())).hasSize(32);
		assertThat(grant.expiresAt()).isEqualTo(expiry);
		assertThat(grant.toString()).doesNotContain(grant.signupToken());
		verify(store).verify("fisher@example.com", "001234", grant.signupToken());
	}
}
