package kr.fishingstory.auth.application;

import java.util.Optional;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.auth.infrastructure.valkey.LoginAttemptLimiter;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailLoginServiceTests {
	static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	LoginAccountStore accounts;
	PasswordEncoder passwords;
	AccessTokenIssuer access;
	RefreshSessionStore sessions;
	LoginAttemptLimiter limiter;
	EmailLoginService service;
	@AfterAll static void close() { FACTORY.close(); }
	@BeforeEach
	void setup() {
		accounts = mock(LoginAccountStore.class);
		passwords = mock(PasswordEncoder.class);
		access = mock(AccessTokenIssuer.class);
		sessions = mock(RefreshSessionStore.class);
		limiter = mock(LoginAttemptLimiter.class);
		when(passwords.encode(anyString())).thenReturn("dummy-hash");
		service = new EmailLoginService(accounts, passwords, access, sessions, limiter, FACTORY.getValidator());
	}
	@Test
	void missingAccountStillChecksPasswordAgainstDummyHash() {
		when(accounts.findEmailAccount("missing@example.com")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.login(new EmailLoginCommand("missing@example.com", "Password1"), "192.0.2.1"))
				.isInstanceOf(ApiException.class).hasMessage("LOGIN_FAILED");
		verify(passwords).matches("Password1", "dummy-hash");
		verifyNoInteractions(access, sessions);
	}
	@Test
	void statusIsCheckedAfterPasswordAndNeverCreatesSessionForPendingAccount() {
		when(accounts.findEmailAccount("fisher@example.com")).thenReturn(Optional.of(
				new LoginAccountStore.Account(123, "USER", "WITHDRAW_PENDING", 0, "account-hash", true)));
		when(passwords.matches("Password1", "account-hash")).thenReturn(true);
		assertThatThrownBy(() -> service.login(new EmailLoginCommand("fisher@example.com", "Password1"), "192.0.2.1"))
				.isInstanceOf(ApiException.class).hasMessage("LOGIN_FAILED");
		verify(passwords).matches("Password1", "account-hash");
		verifyNoInteractions(access, sessions);
	}
	@Test
	void serviceValidationCannotBeBypassedAndSecretsAreRedacted() {
		var invalid = new EmailLoginCommand("bad-email", "Password1");
		assertThatThrownBy(() -> service.login(invalid, "192.0.2.1")).isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
		assertThat(invalid.toString()).doesNotContain("Password1", "bad-email");
		verifyNoInteractions(limiter, accounts, access, sessions);
	}
	@Test
	void limiterFailurePreventsPasswordWorkAndAccountLookup() {
		doThrow(new ApiException(kr.fishingstory.common.error.ErrorCode.LOGIN_RATE_LIMITED, 10L))
				.when(limiter).reserve(anyString(), anyString());
		assertThatThrownBy(() -> service.login(new EmailLoginCommand("fisher@example.com", "Password1"), "192.0.2.1"))
				.isInstanceOf(ApiException.class).hasMessage("LOGIN_RATE_LIMITED");
		verify(passwords, never()).matches(anyString(), anyString());
		verifyNoInteractions(accounts, access, sessions);
	}
}
