package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.PasswordResetCommand;
import kr.fishingstory.auth.application.PasswordResetService;

@RestController
public class PasswordResetController {
	private final PasswordResetService service;
	public PasswordResetController(PasswordResetService service) { this.service = service; }

	@Operation(summary = "이메일 비밀번호 재설정",
			description = "일회성 재설정 토큰을 소비해 비밀번호를 변경하고 모든 Refresh 세션을 종료한다. 기존 Access Token은 최대 15분 유효할 수 있다.")
	@PostMapping("/api/auth/password-reset")
	public ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetCommand command) {
		service.reset(command);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
}
