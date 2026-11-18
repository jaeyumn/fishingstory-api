package kr.fishingstory.auth.api;

import java.time.Instant;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.EmailAddressNormalizer;
import kr.fishingstory.auth.application.PasswordResetEmailRequestService;

@RestController
public class PasswordResetEmailController {
	private final PasswordResetEmailRequestService service;
	public PasswordResetEmailController(PasswordResetEmailRequestService service) { this.service = service; }

	@Operation(summary = "비밀번호 재설정 인증메일 요청",
			description = "계정 존재 여부와 메일 제공자 결과를 노출하지 않고 같은 응답을 반환한다. 이메일·IP 발송 제한은 회원가입과 공유한다.")
	@PostMapping("/api/auth/password-reset/email-code")
	public ResponseEntity<Response> request(@Valid @RequestBody Request request, HttpServletRequest servletRequest) {
		var result = service.request(request.email(), ClientIpAddress.canonical(servletRequest.getRemoteAddr()));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response("ACCEPTED",
				"가입된 이메일이라면 인증메일을 보냈습니다. 메일함을 확인해주세요.",
				result.expiresAt(), result.retryAfterSeconds()));
	}

	public record Request(@NotBlank @Size(max = 320) @Email String email) {
		public Request { email = EmailAddressNormalizer.normalize(email); }
	}
	public record Response(String status, String message, Instant expiresAt, long retryAfterSeconds) { }
}
