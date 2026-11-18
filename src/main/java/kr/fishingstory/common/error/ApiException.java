package kr.fishingstory.common.error;

public class ApiException extends RuntimeException {
	private final ErrorCode errorCode;
	private final Long retryAfterSeconds;
	public ApiException(ErrorCode errorCode) { this(errorCode, null); }
	public ApiException(ErrorCode errorCode, Long retryAfterSeconds) {
		super(errorCode.name());
		this.errorCode = errorCode;
		this.retryAfterSeconds = retryAfterSeconds;
	}
	public ErrorCode errorCode() { return errorCode; }
	public Long retryAfterSeconds() { return retryAfterSeconds; }
}
