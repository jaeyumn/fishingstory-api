package kr.fishingstory.auth.application;

import java.time.Instant;
import jakarta.validation.Validator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.valkey.PasswordChangeAttemptLimiter;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Service
public class PasswordChangeService {
	private final LoginAccountStore accounts;
	private final PasswordEncoder passwords;
	private final RefreshSessionStore sessions;
	private final PasswordChangeAttemptLimiter limiter;
	private final Validator validator;

	public PasswordChangeService(LoginAccountStore accounts, PasswordEncoder passwords,
			RefreshSessionStore sessions, PasswordChangeAttemptLimiter limiter, Validator validator) {
		this.accounts = accounts;
		this.passwords = passwords;
		this.sessions = sessions;
		this.limiter = limiter;
		this.validator = validator;
	}

	@Transactional
	public void change(long userId, PasswordChangeCommand command) {
		if (command == null || !validator.validate(command).isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		// 현재 비밀번호 확인이 로그인 한도를 우회하는 대입 경로가 되지 않도록 잠금·해싱 전에 시도를 예약한다.
		limiter.reserve(userId);
		var account = accounts.lockEmailAccount(userId)
				.orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		var auth = account.auth();
		if (!"ACTIVE".equals(account.status()) || auth.emailVerifiedAt() == null || auth.passwordHash() == null) {
			throw new ApiException(ErrorCode.ACCESS_DENIED);
		}
		if (!passwords.matches(command.currentPassword(), auth.passwordHash())) {
			throw new ApiException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
		}
		if (command.currentPassword().equals(command.newPassword())) {
			throw new ApiException(ErrorCode.PASSWORD_UNCHANGED);
		}
		auth.changePassword(passwords.encode(command.newPassword()), Instant.now());
		accounts.flush();
		// 실패하면 DB 트랜잭션을 롤백한다. 로그인도 같은 회원 행 잠금을 사용하므로 옛 비밀번호 세션이 뒤늦게 생기지 않는다.
		try {
			sessions.logoutAll(userId);
		} catch (ApiException failure) {
			throw failure;
		} catch (org.springframework.dao.DataAccessException unavailable) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}
}
