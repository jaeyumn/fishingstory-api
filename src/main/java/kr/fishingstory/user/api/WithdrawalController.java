package kr.fishingstory.user.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.user.application.WithdrawalRequestCommand;
import kr.fishingstory.user.application.WithdrawalService;

@RestController
public class WithdrawalController {
	private final WithdrawalService service;
	public WithdrawalController(WithdrawalService service) { this.service = service; }

	@Operation(summary = "회원 탈퇴 신청",
			description = "회원을 30일 탈퇴 유예 상태로 전환하고 모든 Refresh 세션을 종료한다. 반복 요청은 최초 신청 시각을 연장하지 않는다.")
	@SecurityRequirement(name = "bearerAuth")
	@PostMapping("/api/users/me/withdrawal")
	public ResponseEntity<WithdrawalService.Result> request(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody WithdrawalRequestCommand command) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.request(AuthenticatedUser.id(jwt), command));
	}
}
