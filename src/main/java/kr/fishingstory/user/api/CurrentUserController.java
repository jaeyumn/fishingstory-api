package kr.fishingstory.user.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.error.ErrorResponse;
import kr.fishingstory.user.query.dto.CurrentUser;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@RestController
public class CurrentUserController {
	private final UserQueryMapper users;
	public CurrentUserController(UserQueryMapper users) { this.users = users; }
	@Operation(summary = "내 정보 조회", description = "유효한 Access JWT의 sub로 본인 정보만 조회한다. 요청으로 회원 ID를 지정하지 않는다. "
			+ "DB 조회 시점에 ACTIVE인 EMAIL 계정의 userId, nickname, provider, email, role을 반환한다. role은 메뉴 표시용이며 관리자 API에서 권한을 다시 검사한다. "
			+ "탈퇴 유예·회원 없음·인증정보 없음·지원하지 않는 로그인 방식은 403/ACCESS_DENIED다. "
			+ "소셜 계정 조회는 후속 단계다. Refresh 세션 존재를 확인하지 않으므로 로그아웃 전 Access는 원래 만료까지 유효하다.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponse(responseCode = "200", description = "본인 정보", content = @Content(schema = @Schema(implementation = CurrentUser.class)))
	@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/api/users/me")
	public ResponseEntity<CurrentUser> me(@AuthenticationPrincipal Jwt jwt) {
		var user = users.findCurrentEmailUser(AuthenticatedUser.id(jwt))
				.orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
	}
}
