package kr.fishingstory.catchreport.query.infrastructure.mybatis;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.catchreport.query.dto.SpeciesCatalogItem;

@Mapper
public interface SpeciesCatalogMapper {
    String COLUMNS = """
            s.species_id, s.name, s.standard_name, s.scientific_name, s.water_type,
            s.family_name, s.family_scientific_name, s.genus_name, s.genus_scientific_name,
            s.entry_kind, s.parent_species_id
            """;

    @Select("SELECT " + COLUMNS + " FROM fishingstory.fish_species s WHERE s.active ORDER BY s.name, s.species_id")
    List<SpeciesCatalogItem.Row> catalog();

    @Select("""
            SELECT a.species_id, a.alias FROM fishingstory.fish_species_alias a
            JOIN fishingstory.fish_species s ON s.species_id = a.species_id
            WHERE s.active ORDER BY a.species_id, a.alias, a.alias_id
            """)
    List<SpeciesCatalogItem.Alias> aliases();

    @Select("SELECT " + COLUMNS + """
            FROM fishingstory.fish_species s WHERE s.species_id = #{speciesId}
            AND (s.active OR EXISTS (SELECT 1 FROM fishingstory.user_fish_collection c
                WHERE c.species_id = s.species_id AND c.user_id = #{userId}))
            """)
    Optional<SpeciesCatalogItem.Row> detail(long userId, long speciesId);

    @Select("SELECT alias FROM fishingstory.fish_species_alias WHERE species_id = #{speciesId} ORDER BY alias, alias_id")
    List<String> speciesAliases(long speciesId);

    @Select("""
            SELECT identification, habitat, distribution, fishing_notes, similar_species
            FROM fishingstory.fish_species WHERE species_id = #{speciesId}
            """)
    SpeciesCatalogItem.Description description(long speciesId);

    @Select("""
            SELECT regulation_id, regulation_type, verification_status, summary, region, period_description,
                   minimum_value, unit, exceptions, legal_basis, source_url, checked_on, effective_from, effective_until
            FROM fishingstory.fish_species_regulation WHERE species_id = #{speciesId} ORDER BY regulation_type, regulation_id
            """)
    List<SpeciesCatalogItem.Regulation> regulations(long speciesId);
}
