package kr.fishingstory.auth.application;

import java.time.Instant;
import java.util.Optional;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTests {
	static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	static final String OLD = "A".repeat(43);
	final RefreshSessionStore.Session session = new RefreshSessionStore.Session(123, "session-id");
	RefreshSessionStore sessions;
	LoginAccountStore accounts;
	AccessTokenIssuer access;
	RefreshTokenService service;
	@AfterAll static void close() { FACTORY.close(); }
	@BeforeEach void setup() {
		sessions = mock(RefreshSessionStore.class);
		accounts = mock(LoginAccountStore.class);
		access = mock(AccessTokenIssuer.class);
		service = new RefreshTokenService(sessions, accounts, access, FACTORY.getValidator());
		when(sessions.findValid(OLD)).thenReturn(Optional.of(session));
		when(accounts.findMember(123)).thenReturn(Optional.of(new LoginAccountStore.Member(123, "ADMIN", "ACTIVE", 7)));
		when(access.issue(123, "ADMIN", session.sessionId(), 7)).thenReturn(new AccessTokenIssuer.Issued("signed", Instant.now().plusSeconds(900)));
	}
	@Test void usesCurrentRoleAndSameSessionAndReturnsTokensOnlyAfterRotation() {
		Instant expiry = Instant.now().plusSeconds(2592000);
		when(sessions.rotate(eq(OLD), anyString(), eq(session))).thenReturn(Optional.of(expiry));
		var result = service.refresh(new RefreshTokenCommand(OLD));
		assertThat(result.refreshToken()).hasSize(43).isNotEqualTo(OLD);
		assertThat(result.refreshTokenExpiresAt()).isEqualTo(expiry);
		var order = inOrder(access, sessions);
		order.verify(sessions).findValid(OLD);
		order.verify(access).issue(123, "ADMIN", session.sessionId(), 7);
		order.verify(sessions).rotate(OLD, result.refreshToken(), session);
		assertThat(result.toString()).doesNotContain(result.refreshToken(), result.accessToken());
	}
	@ParameterizedTest @ValueSource(strings = {"missing", "pending", "role"})
	void invalidMemberNeverSignsOrRotates(String scenario) {
		when(accounts.findMember(123)).thenReturn(scenario.equals("missing") ? Optional.empty() : Optional.of(
				new LoginAccountStore.Member(123, scenario.equals("role") ? "UNKNOWN" : "USER", scenario.equals("pending") ? "WITHDRAW_PENDING" : "ACTIVE", 0)));
		assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(OLD))).isInstanceOf(ApiException.class).hasMessage("REFRESH_INVALID_OR_EXPIRED");
		verifyNoInteractions(access);
		verify(sessions, never()).rotate(anyString(), anyString(), any());
	}
	@Test void missingSessionNeverQueriesDatabase() {
		when(sessions.findValid(OLD)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(OLD))).hasMessage("REFRESH_INVALID_OR_EXPIRED");
		verifyNoInteractions(accounts, access);
	}
	@Test void signingFailurePreservesOldToken() {
		when(access.issue(anyLong(), anyString(), anyString(), anyLong())).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE));
		assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(OLD))).hasMessage("SERVICE_UNAVAILABLE");
		verify(sessions, never()).rotate(anyString(), anyString(), any());
	}
	@Test void lostRaceAndStorageFailureNeverReturnSignedTokens() {
		when(sessions.rotate(eq(OLD), anyString(), eq(session))).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(OLD))).hasMessage("REFRESH_INVALID_OR_EXPIRED");
		when(sessions.rotate(eq(OLD), anyString(), eq(session))).thenThrow(new DataAccessResourceFailureException("private detail"));
		assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(OLD))).isInstanceOf(DataAccessResourceFailureException.class);
	}
	@Test void databaseFailureDoesNotConsumeToken() {
		when(accounts.findMember(123)).thenThrow(new DataAccessResourceFailureException("private detail"));
		assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(OLD))).isInstanceOf(DataAccessResourceFailureException.class);
		verifyNoInteractions(access);
		verify(sessions, never()).rotate(anyString(), anyString(), any());
	}
	@Test void malformedInputCannotBypassServiceValidation() {
		for (String input : new String[] {null, "", " A".repeat(22), "A".repeat(44)}) {
			assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(input))).hasMessage("VALIDATION_ERROR");
		}
		assertThat(new RefreshTokenCommand(OLD).toString()).doesNotContain(OLD);
		verifyNoInteractions(sessions, accounts, access);
	}
}
