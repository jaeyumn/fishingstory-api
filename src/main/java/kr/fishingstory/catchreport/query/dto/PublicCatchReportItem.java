package kr.fishingstory.catchreport.query.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Explicit public response: no account identifiers, location or private image URLs.
public record PublicCatchReportItem(long reportId, String authorNickname, String catchStatus,
		String waterType, LocalDate fishingDate, LocalTime fishingTime, String memo,
		String visibility, List<CatchReportItem.Species> species) { }
