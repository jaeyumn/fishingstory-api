package kr.fishingstory.catchreport.query.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record CatchReportItem(
		long reportId,
		String catchStatus,
		String waterType,
		LocalDate fishingDate,
		LocalTime fishingTime,
		String memo,
		String visibility,
		String locationVisibility,
		Instant createdAt,
		List<Species> species) {
	public record Species(Long speciesId, String name, java.math.BigDecimal maxLengthCm, java.math.BigDecimal maxWeightG, int sortOrder) { }
}
