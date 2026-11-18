package kr.fishingstory.common.error;

import java.util.List;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> application(ApiException failure) {
		return response(failure.errorCode(), failure.retryAfterSeconds(), null, new HttpHeaders());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException failure) {
		var errors = failure.getBindingResult().getFieldErrors().stream()
				.map(error -> new ErrorResponse.FieldError(error.getField(), "입력 형식 또는 필수값을 확인해주세요."))
				.distinct().toList();
		return response(ErrorCode.VALIDATION_ERROR, null, errors, new HttpHeaders());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException failure) {
		return response(ErrorCode.VALIDATION_ERROR, null, null, new HttpHeaders());
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> constraintValidation(ConstraintViolationException failure) {
		return response(ErrorCode.VALIDATION_ERROR, null, null, new HttpHeaders());
	}

	@ExceptionHandler(org.springframework.beans.TypeMismatchException.class)
	public ResponseEntity<ErrorResponse> typeMismatch(org.springframework.beans.TypeMismatchException failure) {
		return response(ErrorCode.VALIDATION_ERROR, null, null, new HttpHeaders());
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class,
			org.springframework.dao.CannotAcquireLockException.class,
			org.springframework.dao.PessimisticLockingFailureException.class,
			org.springframework.transaction.CannotCreateTransactionException.class,
			org.springframework.transaction.TransactionTimedOutException.class})
	public ResponseEntity<ErrorResponse> unavailable(Exception failure) {
		return response(ErrorCode.SERVICE_UNAVAILABLE, null, null, new HttpHeaders());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> unexpected(Exception failure) {
		if (failure instanceof org.springframework.web.ErrorResponse frameworkError) {
			ErrorCode code = switch (frameworkError.getStatusCode().value()) {
				case 400 -> ErrorCode.VALIDATION_ERROR;
				case 404 -> ErrorCode.NOT_FOUND;
				case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
				case 406 -> ErrorCode.NOT_ACCEPTABLE;
				case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
				default -> ErrorCode.INTERNAL_SERVER_ERROR;
			};
			return response(code, null, null, frameworkError.getHeaders());
		}
		return response(ErrorCode.INTERNAL_SERVER_ERROR, null, null, new HttpHeaders());
	}

	private ResponseEntity<ErrorResponse> response(ErrorCode code, Long retryAfterSeconds,
			List<ErrorResponse.FieldError> errors, HttpHeaders headers) {
		var response = ResponseEntity.status(code.status()).headers(headers).cacheControl(CacheControl.noStore());
		if (retryAfterSeconds != null) response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
		return response.body(new ErrorResponse(code, code.message(), retryAfterSeconds, errors));
	}
}
