package kr.fishingstory.catchreport.query.dto;

import java.math.BigDecimal;

public record CatchReportSpeciesRow(long reportId, Long speciesId, String name,
		BigDecimal maxLengthCm, BigDecimal maxWeightG, int sortOrder) { }
