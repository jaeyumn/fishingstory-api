package kr.fishingstory.points.query.application;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

record PointCursor(String name, long id) {
	static PointCursor decode(String encoded, Long regionId, Long zoneId, String waterType, boolean demo) {
		if (encoded == null) return null;
		try {
			if (encoded.isEmpty() || encoded.length() > 1000) throw new IllegalArgumentException();
			var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
			String raw = decoder.decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString();
			String[] values = raw.split("\n", -1);
			if (values.length != 7 || !values[0].equals("1") || !values[1].equals(key(regionId))
					|| !values[2].equals(key(zoneId)) || !values[3].equals(waterType)
					|| !values[4].equals(Boolean.toString(demo)) || !validName(values[5])) throw new IllegalArgumentException();
			long id = Long.parseLong(values[6]);
			if (id <= 0) throw new IllegalArgumentException();
			return new PointCursor(values[5], id);
		} catch (Exception failure) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}
	static String encode(String name, long id, Long regionId, Long zoneId, String waterType, boolean demo) {
		String raw = String.join("\n", "1", key(regionId), key(zoneId), waterType, Boolean.toString(demo), name, Long.toString(id));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
	private static String key(Long id) { return id == null ? "-" : id.toString(); }
	private static boolean validName(String name) {
		return !name.isBlank() && name.codePointCount(0, name.length()) <= 120
				&& name.codePoints().noneMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF));
	}
}
