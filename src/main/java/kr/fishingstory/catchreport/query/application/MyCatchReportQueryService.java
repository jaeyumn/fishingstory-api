package kr.fishingstory.catchreport.query.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.catchreport.query.dto.CatchReportItem;
import kr.fishingstory.catchreport.query.dto.CatchReportRow;
import kr.fishingstory.catchreport.query.dto.CatchReportSpeciesRow;
import kr.fishingstory.catchreport.query.infrastructure.mybatis.CatchReportQueryMapper;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@Service
public class MyCatchReportQueryService {
	private final UserQueryMapper users;
	private final CatchReportQueryMapper reports;

	public MyCatchReportQueryService(UserQueryMapper users, CatchReportQueryMapper reports) {
		this.users = users;
		this.reports = reports;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Page find(long userId, String encodedCursor, int limit, String waterType, String catchStatus,
			Long speciesId) {
		if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
		if (limit < 1 || limit > 50 || waterType == null || catchStatus == null
				|| !List.of("ALL", "SEA", "FRESHWATER").contains(waterType)
				|| !List.of("ALL", "CAUGHT", "NO_CATCH").contains(catchStatus)
				|| (speciesId != null && speciesId <= 0)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		CatchReportCursor cursor = CatchReportCursor.decode(encodedCursor);
		List<CatchReportRow> fetched = reports.findMine(userId,
				cursor == null ? null : cursor.fishingDate(),
				cursor == null ? null : cursor.createdAt(),
				cursor == null ? null : cursor.reportId(), limit + 1,
				waterType, catchStatus, speciesId);
		boolean hasMore = fetched.size() > limit;
		List<CatchReportRow> rows = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
		String nextCursor = hasMore ? CatchReportCursor.encode(rows.getLast()) : null;
		Map<Long, List<CatchReportSpeciesRow>> selected = rows.isEmpty() ? Map.of()
				: reports.findSpecies(rows.stream().map(CatchReportRow::reportId).toList()).stream()
						.collect(Collectors.groupingBy(CatchReportSpeciesRow::reportId));
		List<CatchReportItem> items = rows.stream().map(row -> new CatchReportItem(row.reportId(),
				row.catchStatus(), row.waterType(), row.fishingDate(), row.fishingTime(), row.memo(),
				row.visibility(), row.locationVisibility(), row.createdAt(),
				selected.getOrDefault(row.reportId(), List.of()).stream()
						.map(item -> new CatchReportItem.Species(item.speciesId(), item.name(),
								item.maxLengthCm(), item.maxWeightG(), item.sortOrder())).toList())).toList();
		return new Page(items, nextCursor);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CatchReportItem findOne(long userId, long reportId) {
		if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
		CatchReportRow row = reports.findOneMine(userId, reportId);
		if (row == null) throw new ApiException(ErrorCode.NOT_FOUND);
		List<CatchReportItem.Species> species = reports.findSpecies(List.of(reportId)).stream()
				.map(item -> new CatchReportItem.Species(item.speciesId(), item.name(),
						item.maxLengthCm(), item.maxWeightG(), item.sortOrder())).toList();
		return new CatchReportItem(row.reportId(), row.catchStatus(), row.waterType(),
				row.fishingDate(), row.fishingTime(), row.memo(), row.visibility(),
				row.locationVisibility(), row.createdAt(), species);
	}

	public record Page(List<CatchReportItem> items, String nextCursor) { }
}
