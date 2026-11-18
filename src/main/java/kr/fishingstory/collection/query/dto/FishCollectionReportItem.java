package kr.fishingstory.collection.query.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record FishCollectionReportItem(
		long reportId,
		LocalDate fishingDate,
		LocalTime fishingTime,
		BigDecimal maxLengthCm,
		BigDecimal maxWeightG,
		String memo) { }

