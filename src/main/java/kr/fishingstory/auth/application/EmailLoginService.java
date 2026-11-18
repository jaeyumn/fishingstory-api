package kr.fishingstory.auth.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Validator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.auth.infrastructure.valkey.LoginAttemptLimiter;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Service
public class EmailLoginService {
	private final LoginAccountStore accounts;
	private final PasswordEncoder passwords;
	private final AccessTokenIssuer accessTokens;
	private final RefreshSessionStore sessions;
	private final LoginAttemptLimiter limiter;
	private final Validator validator;
	private final SecureRandom random = new SecureRandom();
	private final String dummyHash;

	public EmailLoginService(LoginAccountStore accounts, PasswordEncoder passwords, AccessTokenIssuer accessTokens,
			RefreshSessionStore sessions, LoginAttemptLimiter limiter, Validator validator) {
		this.accounts = accounts;
		this.passwords = passwords;
		this.accessTokens = accessTokens;
		this.sessions = sessions;
		this.limiter = limiter;
		this.validator = validator;
		this.dummyHash = passwords.encode(randomToken());
	}
	@Transactional
	public Tokens login(EmailLoginCommand command, String canonicalIp) {
		if (command == null || !validator.validate(command).isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		limiter.reserve(command.email(), canonicalIp);
		LoginAccountStore.Account account;
		try { account = accounts.findEmailAccount(command.email()).orElse(null); }
		catch (CannotCreateTransactionException unavailable) { throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE); }
		boolean matches = passwords.matches(command.password(), account == null ? dummyHash : account.passwordHash());
		if (!matches || account == null || !account.emailVerified() || !"ACTIVE".equals(account.status())
				|| !Set.of("USER", "ADMIN").contains(account.role())) throw new ApiException(ErrorCode.LOGIN_FAILED);

		String sessionId = UUID.randomUUID().toString();
		String refreshToken = randomToken();
		var access = accessTokens.issue(account.userId(), account.role(), sessionId, account.accessTokenGeneration());
		// 저장 성공을 확인한 뒤에만 토큰을 반환한다. 응답 유실·저장 결과 불명확 시 미전달 세션은 TTL로 정리된다.
		Instant refreshExpiry = sessions.create(account.userId(), sessionId, refreshToken);
		return new Tokens("Bearer", access.value(), access.expiresAt(), refreshToken, refreshExpiry);
	}
	private String randomToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
	public record Tokens(String tokenType, String accessToken, Instant accessTokenExpiresAt,
			String refreshToken, Instant refreshTokenExpiresAt) {
		@Override public String toString() { return "Tokens[REDACTED]"; }
	}
}
