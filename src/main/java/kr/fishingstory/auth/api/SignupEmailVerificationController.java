package kr.fishingstory.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.EmailAddressNormalizer;
import kr.fishingstory.auth.application.SignupEmailVerificationService;

@RestController
public class SignupEmailVerificationController {
	private final SignupEmailVerificationService service;
	public SignupEmailVerificationController(SignupEmailVerificationService service) { this.service = service; }

	@Operation(summary = "회원가입 이메일 코드 검증", description = "현재 코드를 검증하고 성공 시 1시간 고정 TTL의 가입 토큰을 발급한다. "
			+ "이메일 코드 재사용은 불가하며 실패 5회 시 폐기한다. 회원 생성·로그인은 수행하지 않는다. "
			+ "형식 오류는 검증 횟수에 포함하지 않는다. 토큰 원문은 응답에서만 전달하고 보관/로그에 노출하지 않아야 한다.")
	@PostMapping("/api/auth/signup/email-code/verify")
	public ResponseEntity<SignupEmailVerificationService.Grant> verify(@Valid @RequestBody Request request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(request.email(), request.code()));
	}
	public record Request(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
		public Request { email = EmailAddressNormalizer.normalize(email); }
		@Override public String toString() { return "Request[email=REDACTED, code=REDACTED]"; }
	}
}
