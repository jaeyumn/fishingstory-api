package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.EmailLoginService;
import kr.fishingstory.auth.application.RefreshTokenCommand;
import kr.fishingstory.auth.application.RefreshTokenService;
import kr.fishingstory.common.error.ErrorResponse;

@RestController
public class RefreshTokenController {
	private final RefreshTokenService service;
	public RefreshTokenController(RefreshTokenService service) { this.service = service; }
	@Operation(summary = "로그인 토큰 재발급", description = "Authorization 헤더 없이 Refresh Token만 전달한다. 현재 ACTIVE 회원에 대해 Access Token(15분)과 새 Refresh Token을 발급한다. "
			+ "동일 세션을 유지하고 만료를 성공 시점부터 30일로 갱신한다. 기존 토큰은 폐기되며 동시 요청은 하나만 성공한다. "
			+ "성공 응답 유실 시 이전 토큰으로 복구할 수 없으므로 다시 로그인한다. Reuse Detection은 사용하지 않으며 로그아웃은 /api/auth/logout을 사용한다.")
	@ApiResponse(responseCode = "200", description = "토큰 교체 완료", content = @Content(schema = @Schema(implementation = EmailLoginService.Tokens.class)))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "401", description = "REFRESH_INVALID_OR_EXPIRED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/api/auth/refresh")
	public ResponseEntity<EmailLoginService.Tokens> refresh(@Valid @RequestBody RefreshTokenCommand command) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refresh(command));
	}
}
