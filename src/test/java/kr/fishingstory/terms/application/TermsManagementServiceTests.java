package kr.fishingstory.terms.application;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.terms.domain.Terms;
import kr.fishingstory.terms.infrastructure.TermsCommandStore;
import kr.fishingstory.user.domain.User;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TermsManagementServiceTests {
	UserCommandStore users;
	TermsCommandStore terms;
	SnowflakeIdGenerator ids;
	TermsManagementService service;
	@BeforeEach void prepare() {
		users = mock(UserCommandStore.class);
		terms = mock(TermsCommandStore.class);
		ids = mock(SnowflakeIdGenerator.class);
		User admin = mock(User.class);
		when(admin.status()).thenReturn("ACTIVE");
		when(admin.role()).thenReturn("ADMIN");
		when(users.lock(101)).thenReturn(Optional.of(admin));
		service = new TermsManagementService(users, terms, ids);
	}
	@ParameterizedTest
	@ValueSource(ints = {-86400, 86400})
	void immediatePublicationUsesDatabaseTimeDespiteClockSkew(int databaseOffsetSeconds) {
		Instant databaseNow = Instant.now().plusSeconds(databaseOffsetSeconds);
		var policy = draft(databaseNow, databaseNow.plusSeconds(3600));
		when(terms.currentTime()).thenReturn(databaseNow);

		service.publish(101, 501, true, policy.updatedAt());

		assertThat(policy.published()).isTrue();
		assertThat(policy.updatedAt()).isEqualTo(databaseNow);
		// The sample must follow the potentially blocking locks.
		var order = inOrder(users, terms);
		order.verify(users).lock(101);
		order.verify(terms).lock(501);
		order.verify(terms).currentTime();
		order.verify(terms).flush();
	}
	@ParameterizedTest
	@ValueSource(ints = {-86400, 86400})
	void scheduledPublicationUsesDatabaseTimeInsteadOfTheJvmClock(int databaseOffsetSeconds) {
		Instant databaseNow = Instant.now().plusSeconds(databaseOffsetSeconds);
		var policy = draft(databaseNow, databaseNow.plusSeconds(3600));
		when(terms.currentTime()).thenReturn(databaseNow);

		service.publish(101, 501, false, policy.updatedAt());

		assertThat(policy.published()).isTrue();
		assertThat(policy.updatedAt()).isEqualTo(databaseNow);
		verify(terms).flush();
	}
	@Test void rejectsAnEffectiveTimeAlreadyPastOnTheDatabaseEvenWhenItIsFutureOnTheJvm() {
		Instant databaseNow = Instant.now().plusSeconds(86400);
		var policy = draft(databaseNow, databaseNow.minusSeconds(60));
		Instant reviewed = policy.updatedAt();
		when(terms.currentTime()).thenReturn(databaseNow);

		assertThatThrownBy(() -> service.publish(101, 501, false, reviewed))
				.isInstanceOfSatisfying(ApiException.class,
						failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.STATE_CONFLICT));
		assertThat(policy.published()).isFalse();
		assertThat(policy.updatedAt()).isEqualTo(reviewed);
		verify(terms, never()).flush();
	}
	@Test void draftCreationAndEditingUseTheSameDatabaseClockAsPublication() {
		Instant databaseNow = Instant.now().minusSeconds(86400);
		when(terms.currentTime()).thenReturn(databaseNow, databaseNow.plusSeconds(1));
		when(ids.nextId()).thenReturn(501L);
		var command = new TermsManagementService.Draft("1.0", "약관", "# 내용", databaseNow.plusSeconds(3600));

		assertThat(service.create(101, "TERMS_OF_SERVICE", command)).isEqualTo(501);
		var inserted = org.mockito.ArgumentCaptor.forClass(Terms.class);
		verify(terms).insert(inserted.capture());
		var policy = inserted.getValue();
		assertThat(policy.updatedAt()).isEqualTo(databaseNow);
		when(terms.lock(501)).thenReturn(Optional.of(policy));

		service.edit(101, 501, new TermsManagementService.Draft("1.1", "개정 약관", "# 개정", command.effectiveAt()));

		assertThat(policy.updatedAt()).isEqualTo(databaseNow.plusSeconds(1));
		verify(terms, times(2)).flush();
	}
	private Terms draft(Instant databaseNow, Instant effectiveAt) {
		var policy = new Terms(501L, "TERMS_OF_SERVICE", "1.0", "약관", "# 내용",
				effectiveAt, databaseNow.minusSeconds(3600));
		when(terms.lock(501)).thenReturn(Optional.of(policy));
		return policy;
	}
}
