package kr.fishingstory.points.query.dto;

import java.util.List;

public final class PointViews {
	private PointViews() { }
	public record Bounds(double minLongitude, double minLatitude, double maxLongitude, double maxLatitude) { }
	public record Area(String type, long id, String name, Long regionId, String regionName,
			long pointCount, Bounds bounds, boolean demo) { }
	public record AreaRow(String type, long id, String name, Long regionId, String regionName,
			long pointCount, double minLongitude, double minLatitude, double maxLongitude, double maxLatitude,
			boolean demo) {
		public Area item() {
			return new Area(type, id, name, regionId, regionName, pointCount,
					new Bounds(minLongitude, minLatitude, maxLongitude, maxLatitude), demo);
		}
	}
	public record Point(long pointId, String name, long regionId, String regionName, long zoneId,
			String zoneName, String waterType, double longitude, double latitude, boolean demo) { }
	public record PointRow(long pointId, String name, long regionId, String regionName, long zoneId,
			String zoneName, String waterType, double longitude, double latitude, boolean demo,
			String address, String description) {
		public Point item() {
			return new Point(pointId, name, regionId, regionName, zoneId, zoneName, waterType, longitude, latitude, demo);
		}
	}
	public record Detail(Point point, String address, String description, List<String> aliases) { }
	public record Page(List<Point> items, String nextCursor) { }
	public record Areas(List<Area> items, boolean truncated) { }
	public record Search(List<Area> regions, List<Area> zones, List<Point> points,
			boolean regionsTruncated, boolean zonesTruncated, boolean pointsTruncated) { }
}
