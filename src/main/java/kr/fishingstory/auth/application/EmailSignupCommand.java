package kr.fishingstory.auth.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.fishingstory.user.domain.Nickname;

public record EmailSignupCommand(
		@NotBlank @Email @Size(max = 320) String email,
		@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String signupToken,
		@NotNull @Schema(description = "8~128자, 영문·숫자 포함. 공백 제거·정규화 없이 사용한다.", minLength = 8, maxLength = 128, accessMode = Schema.AccessMode.WRITE_ONLY) String password,
		@NotNull @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String passwordConfirmation,
		@NotBlank @Size(min = 2, max = 20) @Pattern(regexp = "[가-힣A-Za-z0-9 ]+") String nickname,
		@NotNull @AssertTrue Boolean ageConfirmed,
		@NotNull @Positive @Schema(description = "사용자가 동의한 현재 이용약관의 termsId. 이 ID 제출은 해당 버전에 대한 동의를 뜻한다. 개인정보처리방침 ID는 받지 않는다.") Long agreedTermsId) {
	public EmailSignupCommand {
		email = EmailAddressNormalizer.normalize(email);
		nickname = Nickname.normalize(nickname);
	}

	@AssertTrue
	@Schema(hidden = true)
	public boolean isPasswordValid() {
		if (password == null) return true;
		int length = password.codePointCount(0, password.length());
		return length >= 8 && length <= 128 && password.matches("(?s).*[A-Za-z].*")
				&& password.matches("(?s).*[0-9].*")
				&& password.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
	}
	@AssertTrue
	@Schema(hidden = true)
	public boolean isPasswordConfirmed() {
		return password != null && password.equals(passwordConfirmation);
	}
	@Override public String toString() { return "EmailSignupCommand[REDACTED]"; }
}
