package kr.fishingstory.auth.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WithdrawalCancellationCommand(
		@NotBlank @Email @Size(max = 320) String email,
		@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}")
		@Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String cancellationToken) {
	public WithdrawalCancellationCommand {
		email = EmailAddressNormalizer.normalize(email);
	}
	@Override public String toString() { return "WithdrawalCancellationCommand[REDACTED]"; }
}
