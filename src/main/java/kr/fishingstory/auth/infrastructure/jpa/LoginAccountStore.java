package kr.fishingstory.auth.infrastructure.jpa;

import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.auth.domain.UserAuth;
import kr.fishingstory.user.domain.User;

@Repository
public class LoginAccountStore {
	@PersistenceContext private EntityManager entityManager;
	public Optional<Account> findEmailAccount(String email) {
		var auth = entityManager.createQuery("SELECT a FROM UserAuth a WHERE a.provider = 'EMAIL' AND a.email = :email", UserAuth.class)
				.setParameter("email", email).getResultStream().findFirst();
		if (auth.isEmpty()) return Optional.empty();
		var user = entityManager.find(User.class, auth.get().userId(), LockModeType.PESSIMISTIC_WRITE);
		if (user == null) return Optional.empty();
		// 비밀번호 변경을 기다린 로그인은 잠금 획득 뒤 최신 해시를 다시 읽는다.
		entityManager.refresh(auth.get());
		return Optional.of(new Account(user.id(), user.role(), user.status(), user.accessTokenGeneration(),
				auth.get().passwordHash(), auth.get().emailVerifiedAt() != null));
	}
	public Optional<PasswordAccount> lockEmailAccount(long userId) {
		var user = entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
		if (user == null) return Optional.empty();
		var auth = entityManager.createQuery("SELECT a FROM UserAuth a WHERE a.userId = :userId AND a.provider = 'EMAIL'", UserAuth.class)
				.setParameter("userId", userId).getResultStream().findFirst();
		return auth.map(value -> new PasswordAccount(user.status(), value));
	}
	@Transactional(readOnly = true)
	public boolean isPasswordResetEligible(String email) {
		var auth = entityManager.createQuery(
				"SELECT a FROM UserAuth a WHERE a.provider = 'EMAIL' AND a.email = :email", UserAuth.class)
				.setParameter("email", email).getResultStream().findFirst();
		if (auth.isEmpty() || auth.get().passwordHash() == null || auth.get().emailVerifiedAt() == null) return false;
		var user = entityManager.find(User.class, auth.get().userId());
		return user != null && "ACTIVE".equals(user.status());
	}
	public Optional<PasswordAccount> lockEmailAccount(String email) {
		var auth = entityManager.createQuery(
				"SELECT a FROM UserAuth a WHERE a.provider = 'EMAIL' AND a.email = :email", UserAuth.class)
				.setParameter("email", email).getResultStream().findFirst();
		if (auth.isEmpty()) return Optional.empty();
		var user = entityManager.find(User.class, auth.get().userId(), LockModeType.PESSIMISTIC_WRITE);
		if (user == null) return Optional.empty();
		entityManager.refresh(auth.get());
		return Optional.of(new PasswordAccount(user.status(), auth.get()));
	}
	@Transactional(readOnly = true)
	public Optional<java.time.Instant> findPendingWithdrawalRequestedAt(String email) {
		var auth = entityManager.createQuery(
				"SELECT a FROM UserAuth a WHERE a.provider = 'EMAIL' AND a.email = :email", UserAuth.class)
				.setParameter("email", email).getResultStream().findFirst();
		if (auth.isEmpty() || auth.get().emailVerifiedAt() == null) return Optional.empty();
		var user = entityManager.find(User.class, auth.get().userId());
		return user != null && "WITHDRAW_PENDING".equals(user.status())
				? Optional.ofNullable(user.withdrawalRequestedAt()) : Optional.empty();
	}
	public Optional<WithdrawalAccount> lockEmailWithdrawalAccount(String email) {
		var auth = entityManager.createQuery(
				"SELECT a FROM UserAuth a WHERE a.provider = 'EMAIL' AND a.email = :email", UserAuth.class)
				.setParameter("email", email).getResultStream().findFirst();
		if (auth.isEmpty()) return Optional.empty();
		var user = entityManager.find(User.class, auth.get().userId(), LockModeType.PESSIMISTIC_WRITE);
		if (user == null) return Optional.empty();
		entityManager.refresh(auth.get());
		return Optional.of(new WithdrawalAccount(user, auth.get()));
	}
	public void flush() { entityManager.flush(); }
	@Transactional(readOnly = true)
	public Optional<Member> findMember(long userId) {
		var user = entityManager.find(User.class, userId);
		return user == null ? Optional.empty() : Optional.of(new Member(user.id(), user.role(), user.status(), user.accessTokenGeneration()));
	}
	public record Member(long userId, String role, String status, long accessTokenGeneration) { }
	public record PasswordAccount(String status, UserAuth auth) { }
	public record WithdrawalAccount(User user, UserAuth auth) { }
	public record Account(long userId, String role, String status, long accessTokenGeneration,
			String passwordHash, boolean emailVerified) {
		@Override public String toString() { return "Account[REDACTED]"; }
	}
}
