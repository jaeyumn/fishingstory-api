package kr.fishingstory.collection.query.dto;

import java.math.BigDecimal;

public record FishCollectionItem(
		long speciesId,
		String name,
		String scientificName,
		String waterType,
		boolean active,
		boolean acquired,
		BigDecimal maxLengthCm, BigDecimal maxWeightG, long reportCount, java.time.LocalDate lastFishingDate) { }

