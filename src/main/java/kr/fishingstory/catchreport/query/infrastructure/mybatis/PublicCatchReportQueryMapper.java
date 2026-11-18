package kr.fishingstory.catchreport.query.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.catchreport.query.dto.PublicCatchReportRow;

@Mapper
public interface PublicCatchReportQueryMapper {
	@Select("""
			SELECT r.report_id, u.nickname AS author_nickname, r.catch_status, r.water_type,
			       r.fishing_date, r.fishing_time, r.memo, r.created_at
			FROM fishingstory.catch_reports r
			JOIN fishingstory.users u ON u.user_id = r.user_id AND u.status = 'ACTIVE'
			WHERE r.report_id = #{reportId} AND r.visibility = 'PUBLIC'
			""")
	PublicCatchReportRow findOne(@Param("reportId") long reportId);

	@Select("""
			<script>
			SELECT r.report_id, u.nickname AS author_nickname, r.catch_status, r.water_type,
			       r.fishing_date, r.fishing_time, r.memo, r.created_at
			FROM fishingstory.catch_reports r
			JOIN fishingstory.users u ON u.user_id = r.user_id AND u.status = 'ACTIVE'
			WHERE r.visibility = 'PUBLIC'
			<if test="waterType != 'ALL'">AND r.water_type = #{waterType}</if>
			<if test="catchStatus != 'ALL'">AND r.catch_status = #{catchStatus}</if>
			<if test="speciesId != null">
			  AND EXISTS (SELECT 1 FROM fishingstory.catch_report_species selected
			    WHERE selected.report_id = r.report_id AND selected.species_id = #{speciesId})
			</if>
			<if test="cursorDate != null">
			  AND (r.fishing_date, r.created_at, r.report_id)
			      &lt; (#{cursorDate}, #{cursorCreatedAt}, #{cursorReportId})
			</if>
			ORDER BY r.fishing_date DESC, r.created_at DESC, r.report_id DESC
			LIMIT #{limit}
			</script>
			""")
	List<PublicCatchReportRow> find(@Param("cursorDate") LocalDate cursorDate,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorReportId") Long cursorReportId, @Param("limit") int limit,
			@Param("waterType") String waterType, @Param("catchStatus") String catchStatus,
			@Param("speciesId") Long speciesId);
}
