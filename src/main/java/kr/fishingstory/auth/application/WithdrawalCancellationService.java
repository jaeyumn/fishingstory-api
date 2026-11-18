package kr.fishingstory.auth.application;

import java.time.Instant;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.valkey.WithdrawalCancellationVerificationStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.domain.WithdrawalPolicy;

@Service
public class WithdrawalCancellationService {
	private final LoginAccountStore accounts;
	private final WithdrawalCancellationVerificationStore verifications;
	private final Validator validator;

	public WithdrawalCancellationService(LoginAccountStore accounts,
			WithdrawalCancellationVerificationStore verifications, Validator validator) {
		this.accounts = accounts;
		this.verifications = verifications;
		this.validator = validator;
	}

	@Transactional
	public void cancel(WithdrawalCancellationCommand command) {
		if (command == null || !validator.validate(command).isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var account = accounts.lockEmailWithdrawalAccount(command.email())
				.orElseThrow(() -> new ApiException(ErrorCode.WITHDRAWAL_CANCELLATION_INVALID_OR_EXPIRED));
		var user = account.user();
		Instant now = Instant.now();
		if (!"WITHDRAW_PENDING".equals(user.status())
				|| !WithdrawalPolicy.canCancel(user.withdrawalRequestedAt(), now)
				|| account.auth().emailVerifiedAt() == null) {
			throw new ApiException(ErrorCode.WITHDRAWAL_CANCELLATION_INVALID_OR_EXPIRED);
		}
		if (!verifications.consume(command.email(), command.cancellationToken())) {
			throw new ApiException(ErrorCode.WITHDRAWAL_CANCELLATION_INVALID_OR_EXPIRED);
		}
		user.cancelWithdrawal(now);
	}
}
