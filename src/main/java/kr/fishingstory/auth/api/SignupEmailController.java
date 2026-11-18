package kr.fishingstory.auth.api;

import kr.fishingstory.common.error.ErrorCode;

import kr.fishingstory.common.error.ApiException;

import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.SignupEmailRequestService;
import kr.fishingstory.mail.EmailSender;

@RestController
public class SignupEmailController {
	private final SignupEmailRequestService service;
	public SignupEmailController(SignupEmailRequestService service) { this.service = service; }

	@Operation(summary = "회원가입 인증메일 발송", description = "SENT는 제공자 정상 접수이며 배달 완료를 뜻하지 않는다. "
			+ "UNKNOWN이면 메일함을 확인하고 도착한 코드를 사용할 수 있다. 재전송 시 기존 코드는 무효화된다. "
			+ "입력 오류 400, 한도 초과 429(Retry-After), 발송 거절/일시 장애 503. 인증코드는 반환하지 않는다.")
	@PostMapping("/api/auth/signup/email-code")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "제공자 접수 결과",
			content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = Response.class)))
	public ResponseEntity<?> request(@Valid @RequestBody Request request, HttpServletRequest servletRequest) {
		var result = service.request(request.email(), ClientIpAddress.canonical(servletRequest.getRemoteAddr()));
		if (result.outcome().status() == EmailSender.Status.REJECTED) {
			throw new ApiException(ErrorCode.EMAIL_SEND_FAILED,
					result.retryAfterSeconds());
		}
		boolean sent = result.outcome().status() == EmailSender.Status.ACCEPTED;
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response(sent ? "SENT" : "UNKNOWN",
				sent ? "인증메일 발송이 접수되었습니다." : "발송 여부를 확인하지 못했습니다. 메일함을 확인해주세요.",
				result.outcome().expiresAt(), result.retryAfterSeconds()));
	}

	public record Request(@NotBlank @Size(max = 320) @Email String email) {
		public Request {
			email = kr.fishingstory.auth.application.EmailAddressNormalizer.normalize(email);
		}
	}
	public record Response(String status, String message, Instant expiresAt, long retryAfterSeconds) { }
}
