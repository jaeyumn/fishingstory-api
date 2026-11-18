package kr.fishingstory.auth.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefreshTokenCommand(
		@Schema(description = "로그인 또는 직전 재발급으로 받은 Refresh Token")
		@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
	@Override public String toString() { return "RefreshTokenCommand[REDACTED]"; }
}
