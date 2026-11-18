package kr.fishingstory.user.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.common.error.ErrorResponse;
import kr.fishingstory.user.application.NicknameChangeService;
import kr.fishingstory.user.domain.Nickname;

@RestController
public class NicknameChangeController {
	private final NicknameChangeService service;
	public NicknameChangeController(NicknameChangeService service) { this.service = service; }

	@Operation(summary = "내 닉네임 변경", description = "유효한 Access JWT의 sub에 해당하는 ACTIVE 회원의 닉네임을 변경한다. "
			+ "가입과 같은 NFC·공백·문자·길이·대소문자 무시 중복 규칙을 적용한다. 마지막 직접 설정 후 30일이 지나야 변경할 수 있다. "
			+ "회원 행 잠금 안에서 변경 가능 시각을 확인하고 nickname·nickname_key·nickname_changed_at·updated_at을 함께 갱신한다. "
			+ "직접 설정 시각이 있는 회원의 현재 표시 닉네임과 같은 값 재요청은 변경 시각을 갱신하지 않는 멱등 성공이다.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponse(responseCode = "200", description = "닉네임 변경 또는 같은 값 확인", content = @Content(schema = @Schema(implementation = NicknameChangeService.Result.class)))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "NICKNAME_ALREADY_USED / NICKNAME_CHANGE_TOO_SOON; 제한 시 Retry-After 제공", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@PatchMapping("/api/users/me/nickname")
	public ResponseEntity<NicknameChangeService.Result> change(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody Request request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.change(AuthenticatedUser.id(jwt), request.nickname()));
	}

	public record Request(
			@NotBlank @Size(min = 2, max = 20) @Pattern(regexp = "[가-힣A-Za-z0-9 ]+") String nickname) {
		public Request { nickname = Nickname.normalize(nickname); }
	}
}
