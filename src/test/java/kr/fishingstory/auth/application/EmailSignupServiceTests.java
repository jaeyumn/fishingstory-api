package kr.fishingstory.auth.application;

import java.time.Instant;
import java.util.Optional;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import kr.fishingstory.auth.infrastructure.jpa.SignupMemberStore;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.terms.domain.Terms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailSignupServiceTests {
	static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	static final EmailSignupCommand COMMAND = new EmailSignupCommand("fisher@example.com", "A".repeat(43),
			"Password1", "Password1", "Fisher", true, 12L);
	SignupVerificationStore verification;
	SignupMemberStore members;
	PasswordEncoder passwords;
	PlatformTransactionManager manager;
	EmailSignupService service;
	@AfterAll static void close() { FACTORY.close(); }
	@BeforeEach
	void setup() {
		verification = mock(SignupVerificationStore.class);
		members = mock(SignupMemberStore.class);
		passwords = mock(PasswordEncoder.class);
		manager = mock(PlatformTransactionManager.class);
		var ids = mock(SnowflakeIdGenerator.class);
		when(ids.nextId()).thenReturn(1L, 2L, 3L);
		when(verification.findValid(anyString(), anyString())).thenReturn(Optional.of(Instant.now().plusSeconds(3600)));
		when(passwords.encode(anyString())).thenReturn("test-hash");
		var terms = mock(Terms.class);
		when(terms.id()).thenReturn(12L);
		when(terms.required()).thenReturn(true);
		when(terms.requiresConsent()).thenReturn(true);
		when(members.findCurrentTermsOfService()).thenReturn(Optional.of(terms));
		service = new EmailSignupService(verification, members, ids, passwords, FACTORY.getValidator(), manager);
	}
	@Test
	void databaseConnectionFailureLeavesGrantUntouchedAndReturnsUnavailable() {
		when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("private-db-detail"));
		assertThatThrownBy(() -> service.signup(COMMAND)).isInstanceOf(ApiException.class).hasMessage("SERVICE_UNAVAILABLE");
		verify(verification, never()).consume(anyString(), anyString());
		verify(members, never()).insert(any(), any(), any());
	}
	@Test
	void ambiguousCommitFailureNeverDeletesGrantOrReturnsSuccess() {
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		doThrow(new TransactionSystemException("commit result unknown")).when(manager).commit(any());
		assertThatThrownBy(() -> service.signup(COMMAND)).isInstanceOf(TransactionSystemException.class);
		verify(members).insert(any(), any(), any());
		verify(verification, never()).consume(anyString(), anyString());
	}
	@Test
	void directServiceCallCannotBypassAgeValidation() {
		var invalid = new EmailSignupCommand(COMMAND.email(), COMMAND.signupToken(), COMMAND.password(),
				COMMAND.passwordConfirmation(), COMMAND.nickname(), false, 12L);
		assertThatThrownBy(() -> service.signup(invalid)).isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
		verify(verification, never()).findValid(anyString(), anyString());
		verify(passwords, never()).encode(anyString());
		verify(manager, never()).getTransaction(any());
	}
}
