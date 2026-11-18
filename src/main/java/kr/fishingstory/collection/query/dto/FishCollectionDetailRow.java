package kr.fishingstory.collection.query.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FishCollectionDetailRow(
		long speciesId,
		String name,
		String scientificName,
		String waterType,
		boolean active,
		boolean acquired,
		BigDecimal maxLengthCm,
		LocalDate firstFishingDate,
		LocalDate lastFishingDate, BigDecimal maxWeightG, long reportCount) { }

