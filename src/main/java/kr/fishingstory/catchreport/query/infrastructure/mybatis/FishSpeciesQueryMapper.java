package kr.fishingstory.catchreport.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.catchreport.query.dto.FishSpeciesItem;

@Mapper
public interface FishSpeciesQueryMapper {
	@Select("""
			<script>
			SELECT s.species_id, s.name, s.scientific_name, s.water_type
			FROM fishingstory.fish_species s
			WHERE s.active = true
			  AND s.water_type IN (#{waterType}, 'BOTH')
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
	List<FishSpeciesItem> find(@Param("waterType") String waterType, @Param("query") String query);
}
