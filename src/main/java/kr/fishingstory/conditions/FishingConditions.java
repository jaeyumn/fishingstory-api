package kr.fishingstory.conditions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FishingConditions(FishingLocations.Location location, LocalDate date, Instant fetchedAt,
		Section<WeatherHour> weather, WeatherSummary weatherSummary, Section<SeaPeriod> sea,
		Section<TideEvent> tides, Section<TideLevel> tideLevels, Almanac almanac) {
	public record Section<T>(String status, String source, String sourceUrl, String basis,
			Instant fetchedAt, String issuedAt, List<T> items) {
		public Section { items = List.copyOf(items); }
		public Section<T> stale() { return new Section<>("STALE", source, sourceUrl, basis, fetchedAt, issuedAt, items); }
	}
	/** precipitation is KMA's category text such as "1mm 미만" or "30.0~50.0mm"; null means none forecast. */
	public record WeatherHour(String time, Double temperatureC, Double precipitationProbability,
			Double windSpeedMs, Double windDirectionDegrees, String sky, Double humidityPercent, String precipitation) { }
	/** Daily lowest/highest from the same publication as the weather section; either may be absent. */
	public record WeatherSummary(Double lowestC, Double highestC) { }
	public record SeaPeriod(String period, String windDirection, Double windSpeedMinMs,
			Double windSpeedMaxMs, Double waveHeightMinM, Double waveHeightMaxM, String sky) { }
	public record TideEvent(String time, String type, double heightCm) { }
	public record TideLevel(String time, double heightCm) { }
	/** Computed locally. multtae/multtaeStyle are null where 물때 does not apply (freshwater). */
	public record Almanac(int lunarDay, String multtae, String multtaeStyle, String sunrise, String sunset, String basis) { }
}
