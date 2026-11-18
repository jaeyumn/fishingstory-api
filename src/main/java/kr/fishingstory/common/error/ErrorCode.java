package kr.fishingstory.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값을 확인해주세요."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
	STATE_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 요청을 처리할 수 없습니다. 새로고침 후 확인해주세요."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청 처리 중 오류가 발생했습니다."),
	EMAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "인증메일을 보내지 못했습니다."),
	EMAIL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "요청을 완료하지 못했습니다. 메일함을 확인한 뒤 잠시 후 다시 시도해주세요."),
	EMAIL_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 요청해주세요."),
	EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증코드가 일치하지 않습니다."),
	EMAIL_CODE_ATTEMPTS_EXCEEDED(HttpStatus.BAD_REQUEST, "인증코드 입력을 5회 실패했습니다. 새 코드를 요청해주세요."),
	EMAIL_CODE_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "인증코드가 없거나 만료되었습니다. 새 코드를 요청해주세요."),
	RECOVERY_EMAIL_CODE_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "인증코드가 올바르지 않거나 만료되었습니다. 다시 확인하거나 새 코드를 요청해주세요."),
	SIGNUP_VERIFICATION_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "가입 인증이 없거나 만료되었습니다. 이미 가입했다면 로그인하고, 아니라면 이메일을 다시 인증해주세요."),
	SIGNUP_TERMS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 가입에 필요한 이용약관을 확인할 수 없습니다. 잠시 후 다시 시도해주세요."),
	SIGNUP_TERMS_CHANGED(HttpStatus.CONFLICT, "현재 이용약관을 다시 확인하고 동의해주세요."),
	EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다. 로그인해주세요."),
	NICKNAME_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
	NICKNAME_CHANGE_TOO_SOON(HttpStatus.CONFLICT, "닉네임은 마지막 설정 후 30일이 지나야 변경할 수 있습니다."),
	CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호를 확인해주세요."),
	PASSWORD_UNCHANGED(HttpStatus.BAD_REQUEST, "현재 비밀번호와 다른 새 비밀번호를 입력해주세요."),
	PASSWORD_RESET_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "비밀번호 재설정 인증이 없거나 만료되었습니다. 이메일을 다시 인증해주세요."),
	WITHDRAWAL_CANCELLATION_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "탈퇴 취소 인증이 없거나 취소 가능 기간이 지났습니다. 이메일을 다시 인증해주세요."),
	REFRESH_INVALID_OR_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해주세요."),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해주세요."),
	LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "로그인 요청이 많습니다. 잠시 후 다시 시도해주세요."),
	PASSWORD_CHANGE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "비밀번호 변경 시도가 많습니다. 잠시 후 다시 시도해주세요."),
	AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "유효한 로그인이 필요합니다."),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");

	private final HttpStatus status;
	private final String message;
	ErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
	public HttpStatus status() { return status; }
	public String message() { return message; }
}
