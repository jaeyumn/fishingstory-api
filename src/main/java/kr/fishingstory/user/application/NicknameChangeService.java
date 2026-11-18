package kr.fishingstory.user.application;

import java.time.Duration;
import java.time.Instant;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.domain.Nickname;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

@Service
public class NicknameChangeService {
	private static final Duration CHANGE_INTERVAL = Duration.ofDays(30);
	private final UserCommandStore users;
	private final TransactionTemplate transaction;

	public NicknameChangeService(UserCommandStore users, PlatformTransactionManager manager) {
		this.users = users;
		this.transaction = new TransactionTemplate(manager);
	}

	public Result change(long userId, String input) {
		Nickname nickname = Nickname.from(input);
		try {
			Result result = transaction.execute(status -> changeLocked(userId, nickname));
			if (result == null) throw new IllegalStateException("Nickname transaction returned no result");
			return result;
		} catch (RuntimeException failure) {
			throw translateFailure(failure);
		}
	}

	private Result changeLocked(long userId, Nickname nickname) {
		var user = users.lock(userId).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status())) throw new ApiException(ErrorCode.ACCESS_DENIED);
		Instant changedAt = user.nicknameChangedAt();
		if (changedAt != null && nickname.value().equals(user.nickname())) {
			return result(user.nickname(), changedAt);
		}

		Instant now = Instant.now();
		if (changedAt != null) {
			Instant availableAt = changedAt.plus(CHANGE_INTERVAL);
			if (availableAt.isAfter(now)) {
				Duration wait = Duration.between(now, availableAt);
				long seconds = Math.min(CHANGE_INTERVAL.toSeconds(),
						Math.max(1, wait.getSeconds() + (wait.getNano() == 0 ? 0 : 1)));
				throw new ApiException(ErrorCode.NICKNAME_CHANGE_TOO_SOON, seconds);
			}
		}
		user.changeNickname(nickname, now);
		users.flush();
		return result(nickname.value(), now);
	}

	private Result result(String nickname, Instant changedAt) {
		return new Result(nickname, changedAt, changedAt.plus(CHANGE_INTERVAL));
	}

	private RuntimeException translateFailure(RuntimeException failure) {
		if (failure instanceof ApiException) return failure;
		if (failure instanceof CannotCreateTransactionException || failure instanceof TransactionTimedOutException
				|| failure instanceof LockTimeoutException || failure instanceof PessimisticLockException) {
			return new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException constraint
					&& "uq_users_nickname_key".equals(constraint.getConstraintName())) {
				return new ApiException(ErrorCode.NICKNAME_ALREADY_USED);
			}
			if (cause instanceof LockTimeoutException || cause instanceof PessimisticLockException) {
				return new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
			}
		}
		return failure;
	}

	public record Result(String nickname, Instant nicknameChangedAt, Instant nicknameChangeAvailableAt) { }
}
