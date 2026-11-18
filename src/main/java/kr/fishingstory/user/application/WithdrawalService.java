package kr.fishingstory.user.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;
import kr.fishingstory.user.domain.WithdrawalPolicy;

@Service
public class WithdrawalService {
	private final UserCommandStore users;
	private final RefreshSessionStore sessions;
	private final Validator validator;

	public WithdrawalService(UserCommandStore users, RefreshSessionStore sessions, Validator validator) {
		this.users = users;
		this.sessions = sessions;
		this.validator = validator;
	}

	@Transactional
	public Result request(long userId, WithdrawalRequestCommand command) {
		if (command == null || !validator.validate(command).isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var user = users.lock(userId).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status()) && !"WITHDRAW_PENDING".equals(user.status())) {
			throw new ApiException(ErrorCode.ACCESS_DENIED);
		}
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		user.requestWithdrawal(now);
		Instant requestedAt = user.withdrawalRequestedAt();
		users.flush();
		try {
			sessions.logoutAll(userId);
		} catch (ApiException failure) {
			throw failure;
		} catch (DataAccessException unavailable) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return new Result(requestedAt, requestedAt.plus(WithdrawalPolicy.GRACE_PERIOD));
	}

	public record Result(Instant withdrawalRequestedAt, Instant cancellationDeadline) { }
}
