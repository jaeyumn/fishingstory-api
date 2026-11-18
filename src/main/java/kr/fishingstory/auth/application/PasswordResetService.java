package kr.fishingstory.auth.application;

import java.time.Instant;
import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.valkey.PasswordResetVerificationStore;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Service
public class PasswordResetService {
	private final LoginAccountStore accounts;
	private final PasswordResetVerificationStore verifications;
	private final PasswordEncoder passwords;
	private final RefreshSessionStore sessions;
	private final Validator validator;

	public PasswordResetService(LoginAccountStore accounts, PasswordResetVerificationStore verifications,
			PasswordEncoder passwords, RefreshSessionStore sessions, Validator validator) {
		this.accounts = accounts;
		this.verifications = verifications;
		this.passwords = passwords;
		this.sessions = sessions;
		this.validator = validator;
	}

	@Transactional
	public void reset(PasswordResetCommand command) {
		if (command == null || !validator.validate(command).isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (!verifications.isValid(command.email(), command.resetToken())) {
			throw new ApiException(ErrorCode.PASSWORD_RESET_INVALID_OR_EXPIRED);
		}
		var account = accounts.lockEmailAccount(command.email())
				.orElseThrow(() -> new ApiException(ErrorCode.PASSWORD_RESET_INVALID_OR_EXPIRED));
		var auth = account.auth();
		if (!"ACTIVE".equals(account.status()) || auth.emailVerifiedAt() == null || auth.passwordHash() == null) {
			throw new ApiException(ErrorCode.PASSWORD_RESET_INVALID_OR_EXPIRED);
		}
		// 잠금을 기다리는 동안 다른 요청이 토큰을 소비했을 수 있다.
		if (!verifications.isValid(command.email(), command.resetToken())) {
			throw new ApiException(ErrorCode.PASSWORD_RESET_INVALID_OR_EXPIRED);
		}
		if (passwords.matches(command.newPassword(), auth.passwordHash())) {
			throw new ApiException(ErrorCode.PASSWORD_UNCHANGED);
		}
		if (!verifications.consume(command.email(), command.resetToken())) {
			throw new ApiException(ErrorCode.PASSWORD_RESET_INVALID_OR_EXPIRED);
		}
		auth.changePassword(passwords.encode(command.newPassword()), Instant.now());
		accounts.flush();
		try {
			sessions.logoutAll(auth.userId());
		} catch (ApiException failure) {
			throw failure;
		} catch (DataAccessException unavailable) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}
}
