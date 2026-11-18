package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.WithdrawalCancellationCommand;
import kr.fishingstory.auth.application.WithdrawalCancellationService;

@RestController
public class WithdrawalCancellationController {
	private final WithdrawalCancellationService service;
	public WithdrawalCancellationController(WithdrawalCancellationService service) { this.service = service; }

	@Operation(summary = "회원 탈퇴 취소",
			description = "신청 시각부터 30일 미만이고 일회성 이메일 인증이 유효한 경우 계정을 ACTIVE로 복구한다.")
	@PostMapping("/api/auth/withdrawal-cancel")
	public ResponseEntity<Void> cancel(@Valid @RequestBody WithdrawalCancellationCommand command) {
		service.cancel(command);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
}
