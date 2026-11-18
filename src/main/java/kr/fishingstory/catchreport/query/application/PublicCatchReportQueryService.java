package kr.fishingstory.catchreport.query.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.catchreport.query.dto.CatchReportItem;
import kr.fishingstory.catchreport.query.dto.CatchReportSpeciesRow;
import kr.fishingstory.catchreport.query.dto.PublicCatchReportItem;
import kr.fishingstory.catchreport.query.dto.PublicCatchReportRow;
import kr.fishingstory.catchreport.query.infrastructure.mybatis.CatchReportQueryMapper;
import kr.fishingstory.catchreport.query.infrastructure.mybatis.PublicCatchReportQueryMapper;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicCatchReportQueryService {
	private final UserQueryMapper users;
	private final PublicCatchReportQueryMapper reports;
	private final CatchReportQueryMapper species;

	public PublicCatchReportQueryService(UserQueryMapper users, PublicCatchReportQueryMapper reports,
			CatchReportQueryMapper species) {
		this.users = users;
		this.reports = reports;
		this.species = species;
	}

	public Page find(long userId, String encodedCursor, int limit, String waterType,
			String catchStatus, Long speciesId) {
		checkViewer(userId);
		if (limit < 1 || limit > 50 || waterType == null || catchStatus == null
				|| !List.of("ALL", "SEA", "FRESHWATER").contains(waterType)
				|| !List.of("ALL", "CAUGHT", "NO_CATCH").contains(catchStatus)
				|| (speciesId != null && speciesId <= 0)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var cursor = CatchReportCursor.decode(encodedCursor);
		var fetched = reports.find(cursor == null ? null : cursor.fishingDate(),
				cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.reportId(), limit + 1,
				waterType, catchStatus, speciesId);
		boolean hasMore = fetched.size() > limit;
		var rows = hasMore ? fetched.subList(0, limit) : fetched;
		Map<Long, List<CatchReportSpeciesRow>> selected = rows.isEmpty() ? Map.of()
				: species.findSpecies(rows.stream().map(PublicCatchReportRow::reportId).toList()).stream()
						.collect(Collectors.groupingBy(CatchReportSpeciesRow::reportId));
		String nextCursor = null;
		if (hasMore) {
			var last = rows.getLast();
			nextCursor = CatchReportCursor.encode(last.fishingDate(), last.createdAt(), last.reportId());
		}
		return new Page(rows.stream().map(row -> item(row,
				selected.getOrDefault(row.reportId(), List.of()))).toList(), nextCursor);
	}

	public PublicCatchReportItem findOne(long userId, long reportId) {
		checkViewer(userId);
		var row = reports.findOne(reportId);
		if (row == null) throw new ApiException(ErrorCode.NOT_FOUND);
		return item(row, species.findSpecies(List.of(reportId)));
	}

	private void checkViewer(long userId) {
		if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}

	private PublicCatchReportItem item(PublicCatchReportRow row, List<CatchReportSpeciesRow> selected) {
		return new PublicCatchReportItem(row.reportId(), row.authorNickname(), row.catchStatus(),
				row.waterType(), row.fishingDate(), row.fishingTime(), row.memo(), "PUBLIC",
				selected.stream().map(s -> new CatchReportItem.Species(s.speciesId(), s.name(),
						s.maxLengthCm(), s.maxWeightG(), s.sortOrder())).toList());
	}

	public record Page(List<PublicCatchReportItem> items, String nextCursor) { }
}
