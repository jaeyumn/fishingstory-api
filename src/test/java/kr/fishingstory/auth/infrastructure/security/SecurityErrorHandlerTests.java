package kr.fishingstory.auth.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class SecurityErrorHandlerTests {
	@Test
	void filterErrorsUseSharedJsonContractWithoutExceptionDetails() throws Exception {
		var handler = new SecurityErrorHandler(JsonMapper.builder().build());
		var unauthorized = new MockHttpServletResponse();
		handler.commence(new MockHttpServletRequest(), unauthorized, new BadCredentialsException("secret-token"));
		assertThat(unauthorized.getStatus()).isEqualTo(401);
		assertThat(unauthorized.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
		assertThat(unauthorized.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(unauthorized.getContentAsString()).contains("AUTHENTICATION_REQUIRED").doesNotContain("secret-token", "retryAfterSeconds");
		var forbidden = new MockHttpServletResponse();
		handler.handle(new MockHttpServletRequest(), forbidden, new AccessDeniedException("private-permission-detail"));
		assertThat(forbidden.getStatus()).isEqualTo(403);
		assertThat(forbidden.getContentAsString()).contains("ACCESS_DENIED").doesNotContain("private-permission-detail");
	}
}
