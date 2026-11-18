package kr.fishingstory.conditions;

import java.util.List;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import static kr.fishingstory.conditions.FishingLocations.MulttaeStyle.EIGHT;
import static kr.fishingstory.conditions.FishingLocations.MulttaeStyle.SEVEN;

public final class FishingLocations {
	private FishingLocations() { }
	// Weather uses the named city's representative grid, not the tide gauge's coordinates.
	// Coordinates (two decimals, near the tide station or city centre) only drive sunrise/sunset.
	// 물때: the west coast uses 7물때식 (user decision 2026-10-24); 8물때식 for the south/east coasts is an
	// unconfirmed assumption recorded in docs/Fishing_Conditions.md.
	public static final List<Location> ALL = List.of(
			new Location("incheon", "인천", "SEA", 55, 124, "인천 지역 대표 격자", "12A20100", "서해중부앞바다", "DT_0001", "인천", 37.45, 126.59, SEVEN),
			new Location("mokpo", "목포", "SEA", 50, 67, "목포 지역 대표 격자", "12A30100", "서해남부앞바다", "DT_0007", "목포", 34.78, 126.38, SEVEN),
			new Location("busan", "부산", "SEA", 98, 76, "부산 지역 대표 격자", "12B20100", "남해동부앞바다", "DT_0005", "부산", 35.10, 129.04, EIGHT),
			new Location("sokcho", "속초", "SEA", 87, 141, "속초 지역 대표 격자", "12C20100", "동해중부앞바다", "DT_0012", "속초", 38.21, 128.59, EIGHT),
			new Location("seoul", "서울", "FRESHWATER", 60, 127, "서울 지역 대표 격자", null, null, null, null, 37.57, 126.98, null));
	public static Location find(String id) {
		return ALL.stream().filter(value -> value.id().equals(id)).findFirst()
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR));
	}
	public record Location(String id, String name, String waterType, int nx, int ny, String weatherBasis,
			String seaCode, String seaBasis, String tideCode, String tideBasis,
			double latitude, double longitude, MulttaeStyle multtaeStyle) { }
	/** SEVEN: 음력 1일이 7물(7물때식), EIGHT: 음력 1일이 8물(8물때식). */
	public enum MulttaeStyle { SEVEN, EIGHT }
}
