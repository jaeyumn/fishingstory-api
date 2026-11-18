package kr.fishingstory.points.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.points.query.dto.PointViews.AreaRow;
import kr.fishingstory.points.query.dto.PointViews.PointRow;

@Mapper
public interface PointQueryMapper {
	String FROM = """
			FROM fishingstory.fishing_points p
			JOIN fishingstory.fishing_zones z ON z.zone_id=p.zone_id AND z.is_demo=p.is_demo
			JOIN fishingstory.fishing_regions r ON r.region_id=z.region_id AND r.is_demo=z.is_demo
			WHERE p.publication_status='PUBLISHED' AND (#{includeDemo} OR NOT p.is_demo)
			<if test="waterType != 'ALL'">AND p.water_type=#{waterType}</if>
			""";
	String COLUMNS = """
			SELECT p.point_id, p.name, r.region_id, r.name AS region_name, z.zone_id, z.name AS zone_name,
			       p.water_type, p.longitude, p.latitude, p.is_demo AS demo, p.address, p.description
			""";
	String BOUNDS = """
			count(*) AS point_count, min(p.longitude) AS min_longitude, min(p.latitude) AS min_latitude,
			max(p.longitude) AS max_longitude, max(p.latitude) AS max_latitude,
			""";
	String MATCH_POINT = """
			(p.name ILIKE #{pattern} ESCAPE '!' OR EXISTS (
			 SELECT 1 FROM fishingstory.fishing_point_aliases a
			 WHERE a.point_id=p.point_id AND a.alias ILIKE #{pattern} ESCAPE '!'))
			""";

	@Select("SELECT EXISTS (SELECT 1 FROM fishingstory.users WHERE user_id=#{userId} AND status='ACTIVE')")
	boolean active(@Param("userId") long userId);

	@Select("<script> SELECT 'REGION' AS type, r.region_id AS id, r.name, NULL::bigint AS region_id, "
			+ "NULL::text AS region_name, " + BOUNDS + "r.is_demo AS demo " + FROM + """
			<if test="pattern != null">AND r.name ILIKE #{pattern} ESCAPE '!'</if>
			GROUP BY r.region_id
			ORDER BY r.sort_order, r.name COLLATE "C", r.region_id LIMIT #{limit}
			</script>
			""")
	List<AreaRow> regions(@Param("waterType") String waterType, @Param("pattern") String pattern,
			@Param("includeDemo") boolean includeDemo, @Param("limit") int limit);

	@Select("<script> SELECT 'ZONE' AS type, z.zone_id AS id, z.name, r.region_id, r.name AS region_name, "
			+ BOUNDS + "z.is_demo AS demo " + FROM + """
			<if test="regionId != null">AND r.region_id=#{regionId}</if>
			<if test="pattern != null">AND z.name ILIKE #{pattern} ESCAPE '!'</if>
			GROUP BY z.zone_id, r.region_id
			ORDER BY r.sort_order, z.sort_order, z.name COLLATE "C", z.zone_id LIMIT #{limit}
			</script>
			""")
	List<AreaRow> zones(@Param("regionId") Long regionId, @Param("waterType") String waterType,
			@Param("pattern") String pattern, @Param("includeDemo") boolean includeDemo, @Param("limit") int limit);

	@Select("<script>" + COLUMNS + FROM + """
			<if test="regionId != null">AND r.region_id=#{regionId}</if>
			<if test="zoneId != null">AND z.zone_id=#{zoneId}</if>
			<if test="cursorName != null">
			 AND (p.name COLLATE "C",p.point_id) &gt; (#{cursorName} COLLATE "C",#{cursorId})
			</if>
			ORDER BY p.name COLLATE "C", p.point_id LIMIT #{limit}
			</script>
			""")
	List<PointRow> points(@Param("regionId") Long regionId, @Param("zoneId") Long zoneId,
			@Param("waterType") String waterType, @Param("includeDemo") boolean includeDemo,
			@Param("cursorName") String cursorName, @Param("cursorId") Long cursorId, @Param("limit") int limit);

	@Select("<script>" + COLUMNS + FROM + " AND " + MATCH_POINT
			+ " ORDER BY p.name COLLATE \"C\",p.point_id LIMIT #{limit}</script>")
	List<PointRow> search(@Param("pattern") String pattern, @Param("waterType") String waterType,
			@Param("includeDemo") boolean includeDemo, @Param("limit") int limit);

	@Select("<script>" + COLUMNS + FROM + " AND p.point_id=#{pointId}</script>")
	PointRow point(@Param("pointId") long pointId, @Param("waterType") String waterType,
			@Param("includeDemo") boolean includeDemo);

	@Select("SELECT alias FROM fishingstory.fishing_point_aliases WHERE point_id=#{pointId} ORDER BY alias COLLATE \"C\",alias_id")
	List<String> aliases(@Param("pointId") long pointId);
}
