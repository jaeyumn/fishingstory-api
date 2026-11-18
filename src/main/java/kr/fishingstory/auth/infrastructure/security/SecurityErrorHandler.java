package kr.fishingstory.auth.infrastructure.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.error.ErrorResponse;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
	private final ObjectMapper mapper;
	public SecurityErrorHandler(ObjectMapper mapper) { this.mapper = mapper; }
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) throws IOException {
		response.setHeader("WWW-Authenticate", "Bearer");
		write(response, ErrorCode.AUTHENTICATION_REQUIRED);
	}
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure) throws IOException {
		write(response, ErrorCode.ACCESS_DENIED);
	}
	void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, code.message(), null, null));
	}
}
