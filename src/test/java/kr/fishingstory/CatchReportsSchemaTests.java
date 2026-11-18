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
class CatchReportsSchemaTests {
	@Autowired JdbcTemplate jdbc;

	@BeforeEach void clear() {
		jdbc.update("DELETE FROM fishingstory.catch_reports");
	}

	@Test void appliesPrivateDefaultsAndSupportsOptionalFields() {
		jdbc.update("""
				INSERT INTO fishingstory.catch_reports
				(report_id, user_id, catch_status, water_type, fishing_date)
				VALUES (1, 10, 'NO_CATCH', 'FRESHWATER', '2026-09-20')
				""");
		assertThat(jdbc.queryForMap("SELECT * FROM fishingstory.catch_reports WHERE report_id=1"))
				.containsEntry("visibility", "PRIVATE").containsEntry("location_visibility", "PRIVATE")
				.containsEntry("memo", null).containsEntry("fishing_time", null);
	}

	@Test void rejectsUnsupportedCodesAndOversizedMemo() {
		assertThatThrownBy(() -> insert(1, "UNKNOWN", "SEA", "memo"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(2, "NO_CATCH", "OCEAN", "memo"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(3, "NO_CATCH", "SEA", "가".repeat(2001)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test void requestKeyIsOptionalLowercaseHexAndUniquePerMember() {
		insert(1, "NO_CATCH", "SEA", null);
		insert(2, "NO_CATCH", "SEA", null);
		String key = "0123456789abcdef0123456789abcdef";
		insertKey(3, 10, key);
		assertThatThrownBy(() -> insertKey(4, 10, key)).isInstanceOf(DataIntegrityViolationException.class);
		insertKey(5, 11, key);
		assertThatThrownBy(() -> insertKey(6, 10, key.toUpperCase(java.util.Locale.ROOT)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertKey(7, 10, key.substring(1)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports WHERE request_key IS NULL",
				Integer.class)).isEqualTo(2);
	}

	private void insertKey(long id, long userId, String key) {
		jdbc.update("INSERT INTO fishingstory.catch_reports (report_id, user_id, catch_status, water_type, fishing_date, request_key) VALUES (?, ?, 'NO_CATCH', 'SEA', '2026-09-20', ?)",
				id, userId, key);
	}

	private void insert(long id, String status, String water, String memo) {
		jdbc.update("INSERT INTO fishingstory.catch_reports (report_id, user_id, catch_status, water_type, fishing_date, memo) VALUES (?, 10, ?, ?, '2026-09-20', ?)",
				id, status, water, memo);
	}
}
