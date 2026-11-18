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
import kr.fishingstory.auth.application.WithdrawalCancellationEmailVerificationService;

@RestController
public class WithdrawalCancellationEmailVerificationController {
	private final WithdrawalCancellationEmailVerificationService service;
	public WithdrawalCancellationEmailVerificationController(WithdrawalCancellationEmailVerificationService service) {
		this.service = service;
	}

	@Operation(summary = "회원 탈퇴 취소 인증코드 검증",
			description = "취소 전용 코드를 검증하고 1시간 유효한 일회성 취소 토큰을 발급한다.")
	@PostMapping("/api/auth/withdrawal-cancel/email-code/verify")
	public ResponseEntity<WithdrawalCancellationEmailVerificationService.Grant> verify(@Valid @RequestBody Request request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(request.email(), request.code()));
	}

	public record Request(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
		public Request { email = EmailAddressNormalizer.normalize(email); }
		@Override public String toString() { return "Request[email=REDACTED, code=REDACTED]"; }
	}
}
