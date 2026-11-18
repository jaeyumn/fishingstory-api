package kr.fishingstory.user.domain;

import java.text.Normalizer;
import java.util.Locale;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

public record Nickname(String value, String key) {
	public static String normalize(String input) {
		if (input == null) return null;
		return Normalizer.normalize(input, Normalizer.Form.NFC).replaceAll(" +", " ").replaceAll("^ | $", "");
	}
	public static Nickname from(String input) {
		String value = normalize(input);
		if (value == null || !value.matches("[가-힣A-Za-z0-9 ]{2,20}")) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return new Nickname(value, value.toLowerCase(Locale.ROOT));
	}
}
