package kr.fishingstory.auth.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import kr.fishingstory.auth.infrastructure.jpa.LoginAccountStore;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Service
public class RefreshTokenService {
	private final RefreshSessionStore sessions;
	private final LoginAccountStore accounts;
	private final AccessTokenIssuer accessTokens;
	private final Validator validator;
	private final SecureRandom random = new SecureRandom();

	public RefreshTokenService(RefreshSessionStore sessions, LoginAccountStore accounts, AccessTokenIssuer accessTokens, Validator validator) {
		this.sessions = sessions;
		this.accounts = accounts;
		this.accessTokens = accessTokens;
		this.validator = validator;
	}
	public EmailLoginService.Tokens refresh(RefreshTokenCommand command) {
		if (command == null || !validator.validate(command).isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var session = sessions.findValid(command.refreshToken())
				.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID_OR_EXPIRED));
		LoginAccountStore.Member member;
		try {
			member = accounts.findMember(session.userId()).orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID_OR_EXPIRED));
		} catch (CannotCreateTransactionException unavailable) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		if (!"ACTIVE".equals(member.status()) || !Set.of("USER", "ADMIN").contains(member.role())) {
			throw new ApiException(ErrorCode.REFRESH_INVALID_OR_EXPIRED);
		}
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String nextToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		// 서명 실패 시 기존 토큰을 보존한다. 동시 요청의 승자는 Valkey 교체 결과로 결정한다.
		var access = accessTokens.issue(member.userId(), member.role(), session.sessionId(), member.accessTokenGeneration());
		var expiry = sessions.rotate(command.refreshToken(), nextToken, session)
				.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID_OR_EXPIRED));
		return new EmailLoginService.Tokens("Bearer", access.value(), access.expiresAt(), nextToken, expiry);
	}
}
