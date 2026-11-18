package kr.fishingstory.catchreport.query.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record PublicCatchReportRow(long reportId, String authorNickname, String catchStatus,
		String waterType, LocalDate fishingDate, LocalTime fishingTime, String memo, Instant createdAt) { }
