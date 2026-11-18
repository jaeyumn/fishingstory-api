package kr.fishingstory.auth.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmailLoginCommand(@NotBlank @Email @Size(max = 320) String email,
		@NotNull @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, maxLength = 128) String password) {
	public EmailLoginCommand { email = EmailAddressNormalizer.normalize(email); }
	@AssertTrue @Schema(hidden = true)
	public boolean isPasswordInputValid() {
		if (password == null) return true;
		int length = password.codePointCount(0, password.length());
		return length > 0 && length <= 128 && password.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
	}
	@Override public String toString() { return "EmailLoginCommand[REDACTED]"; }
}
