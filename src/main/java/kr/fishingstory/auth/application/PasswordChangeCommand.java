package kr.fishingstory.auth.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record PasswordChangeCommand(
		@NotNull @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, maxLength = 128) String currentPassword,
		@NotNull @Schema(description = "8~128자, 영문·숫자 포함. 공백 제거·정규화 없이 사용한다.",
				accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 8, maxLength = 128) String newPassword,
		@NotNull @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPasswordConfirmation) {
	@AssertTrue @Schema(hidden = true)
	public boolean isCurrentPasswordInputValid() { return validLength(currentPassword, 1); }
	@AssertTrue @Schema(hidden = true)
	public boolean isNewPasswordValid() {
		return newPassword == null || validLength(newPassword, 8) && newPassword.matches("(?s).*[A-Za-z].*")
				&& newPassword.matches("(?s).*[0-9].*");
	}
	@AssertTrue @Schema(hidden = true)
	public boolean isNewPasswordConfirmed() {
		return newPassword != null && newPassword.equals(newPasswordConfirmation);
	}
	private static boolean validLength(String value, int minimum) {
		if (value == null) return true;
		int length = value.codePointCount(0, value.length());
		return length >= minimum && length <= 128
				&& value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
	}
	@Override public String toString() { return "PasswordChangeCommand[REDACTED]"; }
}
