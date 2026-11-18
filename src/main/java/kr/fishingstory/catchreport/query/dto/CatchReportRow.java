package kr.fishingstory.catchreport.query.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record CatchReportRow(long reportId, String catchStatus, String waterType,
		LocalDate fishingDate, LocalTime fishingTime, String memo, String visibility,
		String locationVisibility, Instant createdAt) { }
