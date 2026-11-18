package kr.fishingstory.points.query.application;

import java.text.Normalizer;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.points.infrastructure.PointDemoSettings;
import kr.fishingstory.points.query.dto.PointViews.*;
import kr.fishingstory.points.query.infrastructure.mybatis.PointQueryMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PointQueryService {
	private static final int AREA_LIMIT = 200;
	private static final int SEARCH_LIMIT = 20;
	private final PointQueryMapper points;
	private final PointDemoSettings demo;
	public PointQueryService(PointQueryMapper points, PointDemoSettings demo) { this.points = points; this.demo = demo; }

	public Areas regions(long userId, String waterType) {
		requireActive(userId); requireWater(waterType);
		return areas(points.regions(waterType, null, demo.enabled(), AREA_LIMIT + 1));
	}
	public Areas zones(long userId, long regionId, String waterType) {
		requireActive(userId); requireWater(waterType); requireId(regionId);
		return areas(points.zones(regionId, waterType, null, demo.enabled(), AREA_LIMIT + 1));
	}
	public Page list(long userId, Long regionId, Long zoneId, String waterType, String encodedCursor, int limit) {
		requireActive(userId); requireWater(waterType);
		if (regionId != null) requireId(regionId);
		if (zoneId != null) requireId(zoneId);
		if (limit < 1 || limit > 50) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var cursor = PointCursor.decode(encodedCursor, regionId, zoneId, waterType, demo.enabled());
		var fetched = points.points(regionId, zoneId, waterType, demo.enabled(),
				cursor == null ? null : cursor.name(), cursor == null ? null : cursor.id(), limit + 1);
		boolean more = fetched.size() > limit;
		var rows = fetched.subList(0, Math.min(limit, fetched.size()));
		String next = null;
		if (more) {
			var last = rows.getLast();
			next = PointCursor.encode(last.name(), last.pointId(), regionId, zoneId, waterType, demo.enabled());
		}
		return new Page(rows.stream().map(PointRow::item).toList(), next);
	}
	public Search search(long userId, String query, String waterType) {
		requireActive(userId); requireWater(waterType);
		String pattern = pattern(query);
		var regions = points.regions(waterType, pattern, demo.enabled(), SEARCH_LIMIT + 1);
		var zones = points.zones(null, waterType, pattern, demo.enabled(), SEARCH_LIMIT + 1);
		var found = points.search(pattern, waterType, demo.enabled(), SEARCH_LIMIT + 1);
		return new Search(first(regions, SEARCH_LIMIT).stream().map(AreaRow::item).toList(),
				first(zones, SEARCH_LIMIT).stream().map(AreaRow::item).toList(),
				first(found, SEARCH_LIMIT).stream().map(PointRow::item).toList(),
				regions.size() > SEARCH_LIMIT, zones.size() > SEARCH_LIMIT, found.size() > SEARCH_LIMIT);
	}
	public Detail detail(long userId, long pointId) {
		requireActive(userId); requireId(pointId);
		var row = points.point(pointId, "ALL", demo.enabled());
		if (row == null) throw new ApiException(ErrorCode.NOT_FOUND);
		return new Detail(row.item(), row.address(), row.description(), points.aliases(pointId));
	}
	private Areas areas(List<AreaRow> rows) {
		return new Areas(first(rows, AREA_LIMIT).stream().map(AreaRow::item).toList(), rows.size() > AREA_LIMIT);
	}
	private static <T> List<T> first(List<T> values, int limit) { return values.subList(0, Math.min(limit, values.size())); }
	private void requireActive(long userId) {
		if (!points.active(userId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}
	private static void requireWater(String waterType) {
		if (waterType == null || !List.of("ALL", "SEA", "FRESHWATER").contains(waterType)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
	}
	private static void requireId(long id) { if (id <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR); }
	private static String pattern(String query) {
		if (query == null || query.length() > 240 || query.codePoints().anyMatch(c -> Character.isISOControl(c)
				|| (c >= 0xD800 && c <= 0xDFFF))) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		String clean = Normalizer.normalize(query.strip(), Normalizer.Form.NFC);
		if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 80) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		return "%" + clean.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}
}
