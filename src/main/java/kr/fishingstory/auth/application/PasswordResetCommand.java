package kr.fishingstory.auth.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetCommand(
		@NotBlank @Email @Size(max = 320) String email,
		@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String resetToken,
		@NotNull @Schema(description = "8~128자, 영문·숫자 포함. 공백 제거·정규화 없이 사용한다.",
				accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 8, maxLength = 128) String newPassword,
		@NotNull @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPasswordConfirmation) {
	public PasswordResetCommand {
		email = EmailAddressNormalizer.normalize(email);
	}
	@AssertTrue @Schema(hidden = true)
	public boolean isNewPasswordValid() {
		return newPassword == null || validLength(newPassword) && newPassword.matches("(?s).*[A-Za-z].*")
				&& newPassword.matches("(?s).*[0-9].*");
	}
	@AssertTrue @Schema(hidden = true)
	public boolean isNewPasswordConfirmed() {
		return newPassword != null && newPassword.equals(newPasswordConfirmation);
	}
	private static boolean validLength(String value) {
		int length = value.codePointCount(0, value.length());
		return length >= 8 && length <= 128
				&& value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
	}
	@Override public String toString() { return "PasswordResetCommand[REDACTED]"; }
}
