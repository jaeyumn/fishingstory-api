package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.infrastructure.valkey.RefreshSessionStore;
import kr.fishingstory.common.error.ErrorResponse;

@RestController
public class LogoutController {
	private final RefreshSessionStore sessions;
	public LogoutController(RefreshSessionStore sessions) { this.sessions = sessions; }
	@Operation(summary = "현재 기기 로그아웃", description = "유효한 Access Bearer JWT가 필요하며 요청 본문은 사용하지 않는다. "
			+ "검증된 sub/sid의 Refresh 세션을 종료하고 다른 세션은 유지한다. 이미 종료된 세션도 204다. "
			+ "Access가 만료되면 먼저 Refresh 후 호출한다. 기존 Access Token은 만료까지 최대 15분 유효하다. "
			+ "재발급과 경합해도 204 시점에는 해당 Refresh 세션이 없다. 저장소 장애 또는 반복 경합은 503이며 재시도할 수 있다.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponse(responseCode = "204", description = "현재 Refresh 세션 종료 또는 이미 없음", content = @Content)
	@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/api/auth/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
		sessions.logout(AuthenticatedUser.id(jwt), jwt.getClaimAsString("sid"));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
}
