package kr.fishingstory.user.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CurrentUser(
		@Schema(description = "회원 Snowflake ID", format = "int64") long userId,
		String nickname,
		@Schema(description = "로그인 방식. 현재 EMAIL 계정만 지원", allowableValues = {"EMAIL"}) String provider,
		@Schema(description = "가입 시 정규화한 본인 이메일") String email,
		@Schema(description = "현재 회원 역할", allowableValues = {"USER", "ADMIN"}) String role) {
	@Override public String toString() { return "CurrentUser[REDACTED]"; }
}
