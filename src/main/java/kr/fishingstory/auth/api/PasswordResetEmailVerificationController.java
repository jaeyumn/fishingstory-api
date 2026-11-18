package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.EmailAddressNormalizer;
import kr.fishingstory.auth.application.PasswordResetEmailVerificationService;

@RestController
public class PasswordResetEmailVerificationController {
	private final PasswordResetEmailVerificationService service;
	public PasswordResetEmailVerificationController(PasswordResetEmailVerificationService service) { this.service = service; }

	@Operation(summary = "비밀번호 재설정 인증코드 검증",
			description = "재설정 전용 코드를 검증하고 1시간 유효한 일회성 재설정 토큰을 발급한다.")
	@PostMapping("/api/auth/password-reset/email-code/verify")
	public ResponseEntity<PasswordResetEmailVerificationService.Grant> verify(@Valid @RequestBody Request request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(request.email(), request.code()));
	}

	public record Request(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
		public Request { email = EmailAddressNormalizer.normalize(email); }
		@Override public String toString() { return "Request[email=REDACTED, code=REDACTED]"; }
	}
}
