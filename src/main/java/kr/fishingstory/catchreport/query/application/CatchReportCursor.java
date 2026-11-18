package kr.fishingstory.catchreport.query.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import kr.fishingstory.catchreport.query.dto.CatchReportRow;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

record CatchReportCursor(LocalDate fishingDate, Instant createdAt, long reportId) {
	static CatchReportCursor decode(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\n", -1);
			if (parts.length != 3) throw new IllegalArgumentException();
			return new CatchReportCursor(LocalDate.parse(parts[0]), Instant.parse(parts[1]),
					Long.parseLong(parts[2]));
		} catch (RuntimeException failure) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}

	static String encode(CatchReportRow item) {
		return encode(item.fishingDate(), item.createdAt(), item.reportId());
	}

	static String encode(LocalDate fishingDate, Instant createdAt, long reportId) {
		String raw = fishingDate + "\n" + createdAt + "\n" + reportId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
