package kr.fishingstory.collection.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.collection.query.dto.FishCollectionItem;
import kr.fishingstory.collection.query.dto.FishCollectionProgress;
import kr.fishingstory.collection.query.dto.FishCollectionDetailRow;
import kr.fishingstory.collection.query.dto.FishCollectionReportItem;

@Mapper
public interface FishCollectionQueryMapper {
	@Select("""
			SELECT count(c.collection_id) FILTER (WHERE s.active) AS acquired_count,
			       count(*) FILTER (WHERE s.active) AS total_count
			FROM fishingstory.fish_species s
			LEFT JOIN fishingstory.user_fish_collection c
			  ON c.species_id = s.species_id AND c.user_id = #{userId}
			""")
	FishCollectionProgress progress(@Param("userId") long userId);

	@Select("""
			<script>
			SELECT s.species_id, s.name, s.scientific_name, s.water_type, s.active,
			       (c.collection_id IS NOT NULL) AS acquired, c.max_length_cm, c.max_weight_g,
			       (SELECT count(*) FROM fishingstory.catch_report_species rs
			        JOIN fishingstory.catch_reports r ON r.report_id = rs.report_id
			        WHERE r.user_id = #{userId} AND rs.species_id = s.species_id) AS report_count,
			       c.last_fishing_date
			FROM fishingstory.fish_species s
			LEFT JOIN fishingstory.user_fish_collection c
			  ON c.species_id = s.species_id AND c.user_id = #{userId}
			WHERE (s.active OR c.collection_id IS NOT NULL)
			<if test="status == 'ACQUIRED'">
			  AND c.collection_id IS NOT NULL
			</if>
			<if test="status == 'UNACQUIRED'">
			  AND c.collection_id IS NULL
			</if>
			<if test="waterType != 'ALL'">
			  AND s.water_type IN (#{waterType}, 'BOTH')
			</if>
			<if test="query != null">
			  AND (
			    strpos(lower(s.name), #{query}) > 0
			    OR EXISTS (
			      SELECT 1 FROM fishingstory.fish_species_alias a
			      WHERE a.species_id = s.species_id
			        AND strpos(a.alias_key, #{query}) > 0
			    )
			  )
			</if>
			ORDER BY s.display_order, s.species_id
			</script>
			""")
	List<FishCollectionItem> find(@Param("userId") long userId,
			@Param("status") String status, @Param("waterType") String waterType,
			@Param("query") String query);

	@Select("""
			SELECT s.species_id, s.name, s.scientific_name, s.water_type, s.active,
			       (c.collection_id IS NOT NULL) AS acquired, c.max_length_cm,
			       c.first_fishing_date, c.last_fishing_date, c.max_weight_g,
			       (SELECT count(*) FROM fishingstory.catch_report_species rs
			        JOIN fishingstory.catch_reports r ON r.report_id = rs.report_id
			        WHERE r.user_id = #{userId} AND rs.species_id = s.species_id) AS report_count
			FROM fishingstory.fish_species s
			LEFT JOIN fishingstory.user_fish_collection c
			  ON c.species_id = s.species_id AND c.user_id = #{userId}
			WHERE s.species_id = #{speciesId}
			  AND (s.active OR c.collection_id IS NOT NULL)
			""")
	java.util.Optional<FishCollectionDetailRow> findDetail(
			@Param("userId") long userId, @Param("speciesId") long speciesId);

	@Select("""
			SELECT r.report_id, r.fishing_date, r.fishing_time, rs.max_length_cm, rs.max_weight_g, r.memo
			FROM fishingstory.catch_reports r
			JOIN fishingstory.catch_report_species rs ON rs.report_id = r.report_id
			WHERE r.user_id = #{userId}
			  AND rs.species_id = #{speciesId}
			ORDER BY r.fishing_date DESC, r.created_at DESC, r.report_id DESC
			LIMIT 20
			""")
	List<FishCollectionReportItem> findRecentReports(
			@Param("userId") long userId, @Param("speciesId") long speciesId);
}
