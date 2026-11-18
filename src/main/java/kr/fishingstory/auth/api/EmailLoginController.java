package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.EmailLoginCommand;
import kr.fishingstory.auth.application.EmailLoginService;
import kr.fishingstory.common.error.ErrorResponse;

@RestController
public class EmailLoginController {
	private final EmailLoginService service;
	public EmailLoginController(EmailLoginService service) { this.service = service; }
	@Operation(summary = "이메일 로그인", description = "ACTIVE 이메일 계정의 비밀번호 확인 후 RS256 Access Token(15분)과 기기별 Refresh Token(30일)을 발급한다. "
			+ "이메일은 가입과 같은 규칙으로 정규화하고 비밀번호는 원문 그대로 비교한다. 계정 없음·비밀번호 불일치·탈퇴 유예는 동일한 LOGIN_FAILED다. "
			+ "최근 15분 이메일 10회·실제 연결 IP 50회를 성공/실패 합산하며 Forwarded 헤더는 신뢰하지 않는다. "
			+ "로그인마다 독립 세션을 만든다. 기존 세션은 교체하지 않는다. Refresh 재발급·Rotation은 /api/auth/refresh를 사용한다. 현재 세션 로그아웃은 /api/auth/logout을 사용한다.")
	@ApiResponse(responseCode = "200", description = "토큰 발급 완료", content = @Content(schema = @Schema(implementation = EmailLoginService.Tokens.class)))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "401", description = "LOGIN_FAILED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "429", description = "LOGIN_RATE_LIMITED; Retry-After 제공", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/api/auth/login/email")
	public ResponseEntity<EmailLoginService.Tokens> login(@Valid @RequestBody EmailLoginCommand command, HttpServletRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.login(command, ClientIpAddress.canonical(request.getRemoteAddr())));
	}
}
