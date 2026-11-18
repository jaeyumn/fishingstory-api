package kr.fishingstory;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
class UserTermsAgreementHistorySchemaTests {

	private static final OffsetDateTime RECORDED_AT = OffsetDateTime.parse("2026-09-13T00:00:00Z");

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void prepareReferences() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("DELETE FROM fishingstory.terms");
		jdbc.update("""
				INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at)
				SELECT id, 'fisher' || id, 'fisher' || id, CURRENT_TIMESTAMP
				FROM generate_series(1, 2) AS id
				""");
		jdbc.update("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, requires_consent, is_required, effective_at, published_at)
				SELECT id, 'TERMS_OF_SERVICE', id::text, '이용약관', '# 약관 전문', true, true,
				    TIMESTAMPTZ '2026-09-01 00:00:00+00' + id * INTERVAL '1 day',
				    TIMESTAMPTZ '2026-09-01 00:00:00+00'
				FROM generate_series(1, 2) AS id
				""");
	}

	@Test
	void storesAgreementWithDatabaseTimestampDefault() {
		jdbc.update("""
				INSERT INTO fishingstory.user_terms_agreement_history
				(agreement_history_id, user_id, terms_id, action) VALUES (101, 1, 1, 'AGREED')
				""");
		assertThat(jdbc.queryForMap("SELECT * FROM fishingstory.user_terms_agreement_history"))
				.containsEntry("agreement_history_id", 101L).containsEntry("user_id", 1L)
				.containsEntry("terms_id", 1L).containsEntry("action", "AGREED");
		assertThat(jdbc.queryForObject("SELECT created_at FROM fishingstory.user_terms_agreement_history", OffsetDateTime.class))
				.isNotNull();
	}

	@Test
	void retainsAgreementWithdrawalAndReagreementForSameVersion() {
		insertEvent(101, 1, 1, "AGREED", RECORDED_AT);
		insertEvent(102, 1, 1, "WITHDRAWN", RECORDED_AT.plusSeconds(1));
		insertEvent(103, 1, 1, "AGREED", RECORDED_AT.plusSeconds(2));
		assertThat(jdbc.queryForList("""
				SELECT action FROM fishingstory.user_terms_agreement_history
				WHERE user_id = 1 AND terms_id = 1 ORDER BY created_at, agreement_history_id
				""", String.class)).containsExactly("AGREED", "WITHDRAWN", "AGREED");
	}

	@Test
	void ordersByTimeThenIdWithinUserAndVersion() {
		insertEvent(105, 1, 1, "AGREED", RECORDED_AT.minusSeconds(1));
		insertEvent(102, 1, 1, "WITHDRAWN", RECORDED_AT);
		insertEvent(103, 1, 1, "AGREED", RECORDED_AT);
		insertEvent(104, 2, 1, "AGREED", RECORDED_AT.plusSeconds(1));
		insertEvent(106, 1, 2, "AGREED", RECORDED_AT.plusSeconds(2));
		assertThat(jdbc.queryForList("""
				SELECT agreement_history_id FROM fishingstory.user_terms_agreement_history
				WHERE user_id = ? AND terms_id = ? ORDER BY created_at DESC, agreement_history_id DESC
				""", Long.class, 1L, 1L)).containsExactly(103L, 102L, 105L);
		assertThat(jdbc.queryForList("""
				SELECT action FROM fishingstory.user_terms_agreement_history WHERE user_id = 2 AND terms_id = 2
				""", String.class)).isEmpty();
	}

	@Test
	void rejectsDuplicateEventId() {
		insertEvent(101, 1, 1, "AGREED", RECORDED_AT);
		assertThatThrownBy(() -> insertEvent(101, 2, 2, "WITHDRAWN", RECORDED_AT.plusSeconds(1)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n", "agreed", "UNKNOWN"})
	void rejectsMissingOrUnsupportedActions(String action) {
		assertThatThrownBy(() -> insertEvent(101, 1, 1, action, RECORDED_AT))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"agreement_history_id", "user_id", "terms_id", "created_at"})
	void rejectsNullRequiredValues(String column) {
		insertEvent(101, 1, 1, "AGREED", RECORDED_AT);
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.user_terms_agreement_history SET " + column + " = NULL"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertEvent(long id, long userId, long termsId, String action, OffsetDateTime recordedAt) {
		jdbc.update("""
				INSERT INTO fishingstory.user_terms_agreement_history
				(agreement_history_id, user_id, terms_id, action, created_at) VALUES (?, ?, ?, ?, ?)
				""", id, userId, termsId, action, recordedAt);
	}
}
