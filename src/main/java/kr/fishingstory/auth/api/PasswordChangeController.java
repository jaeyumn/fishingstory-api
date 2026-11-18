package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.PasswordChangeCommand;
import kr.fishingstory.auth.application.PasswordChangeService;
import kr.fishingstory.common.error.ErrorResponse;

@RestController
public class PasswordChangeController {
	private final PasswordChangeService service;
	public PasswordChangeController(PasswordChangeService service) { this.service = service; }

	@Operation(summary = "내 비밀번호 변경", description = "ACTIVE 이메일 회원이 현재 비밀번호를 다시 확인한 뒤 새 비밀번호로 변경한다. "
			+ "새 비밀번호는 가입과 같은 규칙을 사용하며 원문을 정규화하거나 잘라내지 않는다. 성공 시 모든 Refresh 세션을 삭제하므로 모든 기기에서 다시 로그인해야 한다. "
			+ "기존 Access Token은 블랙리스트가 없어 원래 만료까지 최대 15분 유효할 수 있다. "
			+ "형식이 올바른 요청은 성공/실패와 관계없이 회원별 최근 15분 5회로 제한하며 초과 시 현재 비밀번호를 확인하지 않는다.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponse(responseCode = "204", description = "비밀번호 변경 및 모든 Refresh 세션 종료", content = @Content)
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / CURRENT_PASSWORD_MISMATCH / PASSWORD_UNCHANGED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "429", description = "PASSWORD_CHANGE_RATE_LIMITED; Retry-After 제공", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@PatchMapping("/api/users/me/password")
	public ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeCommand command) {
		service.change(AuthenticatedUser.id(jwt), command);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
}
