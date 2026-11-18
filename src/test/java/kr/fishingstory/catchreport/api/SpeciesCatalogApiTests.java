package kr.fishingstory.catchreport.api;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CatchReportApiTests.DatabaseConfiguration.class)
class SpeciesCatalogApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenIssuer tokens;
    @Autowired ObjectMapper json;
    static final long USER = 777777;
    String access;

    @BeforeEach void member() {
        cleanup();
        jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at) VALUES (?, 'CatalogTester','catalogtester',now())", USER);
        jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id,user_id,provider,email,password_hash,email_verified_at) VALUES (777778,?,'EMAIL','catalog@example.test','test',now())", USER);
        access = tokens.issue(USER, "USER", UUID.randomUUID().toString()).value();
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM fishingstory.catch_report_species WHERE report_id IN (SELECT report_id FROM fishingstory.catch_reports WHERE user_id=?)", USER);
        jdbc.update("DELETE FROM fishingstory.catch_reports WHERE user_id=?", USER);
        jdbc.update("DELETE FROM fishingstory.user_fish_collection WHERE user_id=?", USER);
        jdbc.update("DELETE FROM fishingstory.user_auth WHERE user_id=?", USER);
        jdbc.update("DELETE FROM fishingstory.users WHERE user_id=?", USER);
        jdbc.update("DELETE FROM fishingstory.fish_species_alias WHERE alias_id=777779");
        jdbc.update("DELETE FROM fishingstory.fish_species_regulation WHERE regulation_id=777781");
        jdbc.update("UPDATE fishingstory.fish_species SET active=true,standard_name=NULL,family_name=NULL,genus_scientific_name=NULL WHERE species_id=1003");
    }

    String catalog() throws Exception {
        return mvc.perform(get("/api/fish-species/catalog").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(7)).andReturn().getResponse().getContentAsString();
    }

    @Test void providesAllActiveSpeciesAliasesAndContentVersionWithoutUserStatistics() throws Exception {
        String before = json.readTree(catalog()).get("version").asText();
        jdbc.update("UPDATE fishingstory.fish_species SET standard_name='검수용 표준명', family_name='검수용 과', genus_scientific_name='TestGenus' WHERE species_id=1003");
        jdbc.update("INSERT INTO fishingstory.fish_species_alias (alias_id,species_id,alias,alias_key) VALUES (777779,1001,'우럭','우럭')");
        var result = json.readTree(catalog());
        assertThat(result.get("version").asText()).isNotEqualTo(before);
        assertThat(json.readTree(catalog()).get("version").asText()).isEqualTo(result.get("version").asText());
        assertThat(result.get("items").toString()).contains("검수용 표준명", "검수용 과", "TestGenus");
        assertThat(result.get("items").toString()).doesNotContain("reportCount", "acquired");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fish_species_alias WHERE alias_key='우럭'", Integer.class)).isEqualTo(2);
    }

    @Test void unverifiedDetailHasNoInventedRegulationOrTaxonomy() throws Exception {
        mvc.perform(get("/api/fish-species/1003").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.species.aliases[0]").value("우럭"))
            .andExpect(jsonPath("$.species.familyName").doesNotExist())
            .andExpect(jsonPath("$.regulations.length()").value(0));
    }

    @Test void catalogAndDetailRequireActiveMemberAndHideUnownedInactiveSpecies() throws Exception {
        mvc.perform(get("/api/fish-species/catalog")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE fishingstory.fish_species SET active=false WHERE species_id=1003");
        mvc.perform(get("/api/fish-species/1003").header("Authorization", "Bearer " + access)).andExpect(status().isNotFound());
        jdbc.update("INSERT INTO fishingstory.user_fish_collection (collection_id,user_id,species_id,first_fishing_date,last_fishing_date) VALUES (777780,?,1003,'2026-09-01','2026-09-01')", USER);
        mvc.perform(get("/api/fish-species/1003").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=?", USER);
        mvc.perform(get("/api/fish-species/catalog").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
    }

    @Test void regulationsAreAdvisoryAndNeverBlockCatchCreation() throws Exception {
        jdbc.update("""
            INSERT INTO fishingstory.fish_species_regulation
            (regulation_id,species_id,regulation_type,verification_status,summary,region,legal_basis,source_url,checked_on)
            VALUES (777781,1003,'CAPTURE_BAN','VERIFIED','테스트 전용 포획금지 안내','테스트 지역','가상 테스트 근거','https://example.test/rule',CURRENT_DATE)
            """);
        mvc.perform(get("/api/fish-species/1003").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.regulations[0].verificationStatus").value("VERIFIED"));
        mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON).content("""
            {"catchStatus":"CAUGHT","waterType":"SEA","fishingDate":"2026-09-20","species":[{"speciesId":1003}]}
            """)).andExpect(status().isCreated());
    }
}
