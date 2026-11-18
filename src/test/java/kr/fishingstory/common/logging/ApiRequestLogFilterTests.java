package kr.fishingstory.common.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;

class ApiRequestLogFilterTests {

	@Test void recordsSafeMetadataAndIgnoresClientIdentifiersAndSecrets() throws Exception {
		var logger = (Logger) LoggerFactory.getLogger(ApiRequestLogFilter.class);
		var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
		var request = new MockHttpServletRequest("POST", "/api/inquiries/private-email@example.com");
		request.setQueryString("token=secret-query");
		request.addHeader("Authorization", "Bearer secret-access");
		request.addHeader("X-Request-Id", "untrusted-client-id");
		request.setContent("password=secret-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var response = new MockHttpServletResponse();
		var handler = new HandlerMethod(this, getClass().getDeclaredMethod("endpoint"));
		MDC.put("requestId", "outer-context");
		try {
			new ApiRequestLogFilter().doFilter(request, response, (req, res) -> {
				req.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
				assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
				response.setStatus(403);
			});
			assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
			assertThat(MDC.get("requestId")).isEqualTo("outer-context");
			assertThat(appender.list).hasSize(1);
			assertThat(appender.list.getFirst().getFormattedMessage()).contains("status=403", "operation=ApiRequestLogFilterTests.endpoint")
					.doesNotContain("private-email", "secret-", "untrusted-client-id", "outer-context");
		} finally { MDC.remove("requestId"); logger.detachAppender(appender); appender.stop(); }
	}

	@Test void unhandledFailureDoesNotLogExceptionTextOrLeakThreadContext() {
		var logger = (Logger) LoggerFactory.getLogger(ApiRequestLogFilter.class);
		var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> new ApiRequestLogFilter().doFilter(new MockHttpServletRequest("POST", "/api/auth/login/email"),
					new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("secret-password"); }))
					.isInstanceOf(IllegalStateException.class);
			assertThat(appender.list).hasSize(1);
			assertThat(appender.list.getFirst().getFormattedMessage()).contains("status=500", "operation=UNMAPPED").doesNotContain("secret-password");
			assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
			assertThat(MDC.get("requestId")).isNull();
		} finally { logger.detachAppender(appender); appender.stop(); }
	}

	public void endpoint() { }
}
