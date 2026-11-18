package kr.fishingstory.auth.api;

import org.springframework.security.oauth2.jwt.Jwt;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

/** 검증된 Access JWT의 sub를 회원 ID로 읽는다. 요청 본문·경로의 회원 ID는 사용하지 않는다. */
public final class AuthenticatedUser {
	private AuthenticatedUser() { }

	public static long id(Jwt jwt) {
		if (jwt == null) throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED);
		try {
			return Long.parseLong(jwt.getSubject());
		} catch (RuntimeException failure) {
			throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
	}
}
