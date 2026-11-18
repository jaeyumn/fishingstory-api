package kr.fishingstory.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.application.EmailSignupCommand;
import kr.fishingstory.auth.application.EmailSignupService;
import kr.fishingstory.common.error.ErrorResponse;

@RestController
public class EmailSignupController {
	private final EmailSignupService service;
	public EmailSignupController(EmailSignupService service) { this.service = service; }

	@Operation(summary = "이메일 회원 생성", description = "인증한 이메일·가입 토큰, 비밀번호/확인, 닉네임, 만 14세 이상 확인과 동의한 이용약관 ID를 검증한다. "
			+ "회원·이메일 인증정보·AGREED 이력을 함께 저장하고 커밋 후 인증 토큰을 정리한다. 권한은 USER, 상태는 ACTIVE로 지정한다. "
			+ "201은 회원 생성 완료이며 로그인 토큰은 발급하지 않는다. DB 실패 시 원래 인증 만료 전 재시도할 수 있다. "
			+ "성공 응답 유실 시 재요청으로 계정을 변경하거나 로그인하지 않는다. EMAIL_ALREADY_REGISTERED이면 일반 로그인으로 이동한다. "
			+ "인증이 삭제되거나 만료된 재요청에는 SIGNUP_VERIFICATION_INVALID_OR_EXPIRED를 반환하며, 계정 상태 확인은 일반 로그인으로 진행한다.")
	@ApiResponse(responseCode = "201", description = "회원 생성 완료")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / SIGNUP_VERIFICATION_INVALID_OR_EXPIRED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED / NICKNAME_ALREADY_USED / SIGNUP_TERMS_CHANGED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "SIGNUP_TERMS_UNAVAILABLE / SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/api/auth/signup/email")
	public ResponseEntity<EmailSignupService.Result> signup(@Valid @RequestBody EmailSignupCommand command) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.signup(command));
	}
}
