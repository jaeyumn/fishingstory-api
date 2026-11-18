package kr.fishingstory.catchreport.query.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.catchreport.query.dto.CatchReportRow;
import kr.fishingstory.catchreport.query.dto.CatchReportSpeciesRow;

@Mapper
public interface CatchReportQueryMapper {
	@Select("""
			SELECT r.report_id, r.catch_status, r.water_type, r.fishing_date, r.fishing_time,
			       r.memo, r.visibility, r.location_visibility, r.created_at
			FROM fishingstory.catch_reports r
			JOIN fishingstory.users u ON u.user_id = r.user_id
			WHERE r.report_id = #{reportId} AND r.user_id = #{userId} AND u.status = 'ACTIVE'
			""")
	CatchReportRow findOneMine(@Param("userId") long userId, @Param("reportId") long reportId);

	@Select("""
			<script>
			SELECT r.report_id, r.catch_status, r.water_type, r.fishing_date, r.fishing_time,
			       r.memo, r.visibility, r.location_visibility, r.created_at
			FROM fishingstory.catch_reports r
			JOIN fishingstory.users u ON u.user_id = r.user_id
			WHERE r.user_id = #{userId}
			  AND u.status = 'ACTIVE'
			<if test="waterType != 'ALL'">
			  AND r.water_type = #{waterType}
			</if>
			<if test="catchStatus != 'ALL'">
			  AND r.catch_status = #{catchStatus}
			</if>
			<if test="speciesId != null">
			  AND EXISTS (
			    SELECT 1 FROM fishingstory.catch_report_species selected
			    WHERE selected.report_id = r.report_id AND selected.species_id = #{speciesId}
			  )
			</if>
			<if test="cursorDate != null">
			  AND (r.fishing_date, r.created_at, r.report_id)
			      &lt; (#{cursorDate}, #{cursorCreatedAt}, #{cursorReportId})
			</if>
			ORDER BY r.fishing_date DESC, r.created_at DESC, r.report_id DESC
			LIMIT #{limit}
			</script>
			""")
	List<CatchReportRow> findMine(
			@Param("userId") long userId,
			@Param("cursorDate") LocalDate cursorDate,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorReportId") Long cursorReportId,
			@Param("limit") int limit,
			@Param("waterType") String waterType,
			@Param("catchStatus") String catchStatus,
			@Param("speciesId") Long speciesId);

	@Select("""
			<script>
			SELECT rs.report_id, rs.species_id, COALESCE(rs.custom_name, s.name) AS name, rs.max_length_cm, rs.max_weight_g, rs.sort_order
			FROM fishingstory.catch_report_species rs
			LEFT JOIN fishingstory.fish_species s ON s.species_id = rs.species_id
			WHERE rs.report_id IN
			<foreach item="reportId" collection="reportIds" open="(" separator="," close=")">
			  #{reportId}
			</foreach>
			ORDER BY rs.report_id, rs.sort_order
			</script>
			""")
	List<CatchReportSpeciesRow> findSpecies(@Param("reportIds") List<Long> reportIds);
}
