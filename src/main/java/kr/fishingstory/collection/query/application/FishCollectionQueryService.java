package kr.fishingstory.collection.query.application;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.collection.query.dto.FishCollectionItem;
import kr.fishingstory.collection.query.dto.FishCollectionReportItem;
import kr.fishingstory.collection.query.infrastructure.mybatis.FishCollectionQueryMapper;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FishCollectionQueryService {
	private final UserQueryMapper users;
	private final FishCollectionQueryMapper collection;

	public FishCollectionQueryService(UserQueryMapper users, FishCollectionQueryMapper collection) {
		this.users = users;
		this.collection = collection;
	}

	public Result find(long userId, String status, String waterType, String input) {
		if (!("ALL".equals(status) || "ACQUIRED".equals(status) || "UNACQUIRED".equals(status))
				|| !("ALL".equals(waterType) || "SEA".equals(waterType) || "FRESHWATER".equals(waterType))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
		if (input != null && input.indexOf('\0') >= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		String query = input == null || input.isBlank() ? null : input.strip().toLowerCase(Locale.ROOT);
		var progress = collection.progress(userId);
		return new Result(progress.acquiredCount(), progress.totalCount(),
				List.copyOf(collection.find(userId, status, waterType, query)));
	}

	public Detail detail(long userId, long speciesId) {
		if (speciesId <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
		var item = collection.findDetail(userId, speciesId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<FishCollectionReportItem> reports = item.acquired()
				? List.copyOf(collection.findRecentReports(userId, speciesId)) : List.of();
		return new Detail(item.speciesId(), item.name(), item.scientificName(), item.waterType(),
				item.active(), item.acquired(), item.maxLengthCm(), item.firstFishingDate(),
				item.lastFishingDate(), item.maxWeightG(), item.reportCount(), reports);
	}

	public record Result(long acquiredCount, long totalCount, List<FishCollectionItem> items) { }
	public record Detail(long speciesId, String name, String scientificName, String waterType,
			boolean active, boolean acquired, java.math.BigDecimal maxLengthCm,
			java.time.LocalDate firstFishingDate, java.time.LocalDate lastFishingDate,
			java.math.BigDecimal maxWeightG, long reportCount, List<FishCollectionReportItem> reports) { }
}
