package kr.fishingstory.auth.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import kr.fishingstory.common.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTests {
	@Test
	void readsTheVerifiedSubjectAndRejectsMissingOrMalformedAuthentication() {
		assertThat(AuthenticatedUser.id(jwt("9007199254740993"))).isEqualTo(9007199254740993L);
		for (Jwt invalid : new Jwt[] {null, jwt("not-a-number"), jwt("")}) {
			assertThatThrownBy(() -> AuthenticatedUser.id(invalid))
					.isInstanceOf(ApiException.class).hasMessage("AUTHENTICATION_REQUIRED");
		}
	}

	private static Jwt jwt(String subject) {
		return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).build();
	}
}
