package kr.fishingstory.common.error;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ErrorCode code, String message, Long retryAfterSeconds, List<FieldError> errors) {
	public record FieldError(String field, String reason) { }
}
