package kr.fishingstory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FishingstoryApiApplicationTests.DatabaseConfiguration.class)
class FishCollectionSchemaTests {
	@Autowired JdbcTemplate jdbc;

	@BeforeEach void clearProjection() {
		jdbc.update("DELETE FROM fishingstory.fish_species_regulation");
		jdbc.update("DELETE FROM fishingstory.user_fish_collection");
		jdbc.update("DELETE FROM fishingstory.catch_report_species");
	}

	@Test void seedsReservedMasterSpeciesAndAlias() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fish_species", Integer.class)).isEqualTo(7);
		assertThat(jdbc.queryForObject("SELECT name FROM fishingstory.fish_species WHERE species_id=1003", String.class))
				.isEqualTo("조피볼락");
		assertThat(jdbc.queryForObject("SELECT alias FROM fishingstory.fish_species_alias WHERE alias_id=3001", String.class))
				.isEqualTo("우럭");
	}

	@Test void enforcesUniqueReportSpeciesLengthAndOrder() {
		insertReportSpecies(1, 10, 1001, "42.5", 0);
		assertThatThrownBy(() -> insertReportSpecies(2, 10, 1001, null, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertReportSpecies(3, 11, 1002, "0", 0))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertReportSpecies(4, 12, 1002, "1000.1", 0))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertReportSpecies(5, 13, 1002, null, -1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test void enforcesOneProjectionAndOrderedDates() {
		insertCollection(1, 10, 1001, "42.5", "2026-09-10", "2026-09-20");
		assertThatThrownBy(() -> insertCollection(2, 10, 1001, null, "2026-09-01", "2026-09-20"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertCollection(3, 10, 1002, null, "2026-09-21", "2026-09-20"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test void requiresExactlyOneSpeciesSourceAndUniqueCustomNames() {
		jdbc.update("""
				INSERT INTO fishingstory.catch_report_species
				(report_species_id, report_id, custom_name, sort_order) VALUES (90, 10, '기타 어종', 0)
				""");
		assertThat(jdbc.queryForObject("SELECT custom_name FROM fishingstory.catch_report_species WHERE report_species_id=90",
				String.class)).isEqualTo("기타 어종");
		for (String values : java.util.List.of(
				"(91, 10, NULL, NULL, 1)", "(92, 10, 1001, '기타', 1)",
				"(93, 10, NULL, ' ', 1)", "(94, 10, NULL, '기타 어종', 1)")) {
			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO fishingstory.catch_report_species
					(report_species_id, report_id, species_id, custom_name, sort_order) VALUES
					""" + values)).isInstanceOf(DataIntegrityViolationException.class);
		}
		// The same label on another report is valid; master constraints remain in place.
		jdbc.update("""
				INSERT INTO fishingstory.catch_report_species
				(report_species_id, report_id, custom_name, sort_order) VALUES (95, 11, '기타 어종', 0)
				""");
		insertReportSpecies(96, 10, 1001, null, 1);
	}

	@Test void requiresEvidenceForConfirmedRegulationAndSupportsUnknownWithoutLegalClaims() {
		jdbc.update("""
				INSERT INTO fishingstory.fish_species_regulation
				(regulation_id,species_id,regulation_type) VALUES (800,1003,'CLOSED_SEASON')
				""");
		assertThat(jdbc.queryForObject("SELECT verification_status FROM fishingstory.fish_species_regulation WHERE regulation_id=800", String.class)).isEqualTo("UNKNOWN");
		for (String status : java.util.List.of("VERIFIED", "NO_RULE", "NOT_APPLICABLE")) {
			assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.fish_species_regulation SET verification_status=? WHERE regulation_id=800", status))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		insertReportSpecies(801, 802, 1003, null, 0);
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.catch_report_species SET max_weight_g=0"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test void weightStopsAtTwoTonnesInReportsAndCollection() {
		insertReportSpecies(811, 812, 1003, null, 0);
		insertCollection(813, 814, 1003, null, "2026-09-20", "2026-09-20");
		for (String table : java.util.List.of("catch_report_species", "user_fish_collection")) {
			jdbc.update("UPDATE fishingstory." + table + " SET max_weight_g=2000000.0");
			assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory." + table + " SET max_weight_g=2000000.1"))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test void varietyRelationshipRequiresParentAndDoesNotEraseExistingSpecies() {
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.fish_species SET entry_kind='CULTIVAR' WHERE species_id=2002"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.fish_species SET parent_species_id=2001 WHERE species_id=2002"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fish_species WHERE standard_name IS NULL AND entry_kind IS NULL", Integer.class)).isEqualTo(7);
	}

	private void insertReportSpecies(long id, long reportId, long speciesId, String length, int order) {
		jdbc.update("""
				INSERT INTO fishingstory.catch_report_species
				(report_species_id, report_id, species_id, max_length_cm, sort_order)
				VALUES (?, ?, ?, ?::numeric, ?)
				""", id, reportId, speciesId, length, order);
	}

	private void insertCollection(long id, long userId, long speciesId, String length,
			String firstDate, String lastDate) {
		jdbc.update("""
				INSERT INTO fishingstory.user_fish_collection
				(collection_id, user_id, species_id, max_length_cm, first_fishing_date, last_fishing_date)
				VALUES (?, ?, ?, ?::numeric, ?::date, ?::date)
				""", id, userId, speciesId, length, firstDate, lastDate);
	}
}
