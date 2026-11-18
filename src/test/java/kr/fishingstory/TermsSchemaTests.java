package kr.fishingstory;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
class TermsSchemaTests {

	private static final OffsetDateTime EFFECTIVE_AT = OffsetDateTime.parse("2026-10-01T00:00:00Z");
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clearTerms() {
		jdbc.update("DELETE FROM fishingstory.terms");
	}

	@Test
	void storesMarkdownDraftWithTimestampDefaults() {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, null, true, true);
		String markdown = "# 이용약관\n\n- **필수** 항목\n- [안내](https://example.com/policy)";
		jdbc.update("UPDATE fishingstory.terms SET content = ? WHERE terms_id = 1", markdown);
		var terms = jdbc.queryForMap("SELECT * FROM fishingstory.terms WHERE terms_id = 1");
		assertThat(terms).containsEntry("content", markdown).containsEntry("published_at", null);
		assertThat(terms.get("created_at")).isNotNull();
		assertThat(terms.get("updated_at")).isNotNull();
	}

	@Test
	void rejectsDuplicateVersionEvenWithDifferentEffectiveTime() {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, null, true, true);
		assertThatThrownBy(() -> insertTerms(2L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT.plusDays(1), null, true, true))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_terms_policy_version");
	}

	@Test
	void rejectsDuplicateEffectiveTimeIncludingDraftsAndEquivalentTimezones() {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, null, true, true);
		assertThatThrownBy(() -> insertTerms(2L, "TERMS_OF_SERVICE", "2.0",
				OffsetDateTime.parse("2026-10-01T09:00:00+09:00"), EFFECTIVE_AT.minusDays(7), true, true))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_terms_policy_effective_at");
	}

	@Test
	void permitsSameVersionAndEffectiveTimeAcrossPolicyTypes() {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, EFFECTIVE_AT, true, true);
		insertTerms(2L, "PRIVACY_POLICY", "1.0", EFFECTIVE_AT, EFFECTIVE_AT, false, false);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.terms", Integer.class)).isEqualTo(2);
	}

	@Test
	void permitsAdvancePublicationAndRejectsRetroactivePublication() {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, EFFECTIVE_AT.minusDays(7), true, true);
		assertThatThrownBy(() -> insertTerms(2L, "TERMS_OF_SERVICE", "2.0", EFFECTIVE_AT.plusDays(1),
				EFFECTIVE_AT.plusDays(1).plusNanos(1000), true, true))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_terms_publication");
	}

	@ParameterizedTest
	@MethodSource("invalidConsent")
	void rejectsInvalidConsentCombinations(String policy, Boolean consent, Boolean required) {
		assertThatThrownBy(() -> insertTerms(1L, policy, "1.0", EFFECTIVE_AT, null, consent, required))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	static Stream<Arguments> invalidConsent() {
		return Stream.of("TERMS_OF_SERVICE", "PRIVACY_POLICY").flatMap(policy ->
				Stream.of(Boolean.TRUE, Boolean.FALSE).flatMap(consent ->
						Stream.of(Boolean.TRUE, Boolean.FALSE).filter(required ->
								!(consent == policy.equals("TERMS_OF_SERVICE") && required == consent))
								.map(required -> Arguments.of(policy, consent, required))));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"AGE_14", "UNKNOWN", "terms_of_service"})
	void rejectsUnsupportedPolicyTypes(String policy) {
		assertThatThrownBy(() -> insertTerms(1L, policy, "1.0", EFFECTIVE_AT, null, true, true))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n"})
	void rejectsMissingOrBlankText(String value) {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, null, true, true);
		for (String column : new String[] {"version", "title", "content"}) {
			assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.terms SET " + column + " = ? WHERE terms_id = 1", value))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"terms_id", "requires_consent", "is_required", "effective_at", "created_at", "updated_at"})
	void rejectsNullRequiredColumns(String column) {
		insertTerms(1L, "TERMS_OF_SERVICE", "1.0", EFFECTIVE_AT, null, true, true);
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.terms SET " + column + " = NULL WHERE terms_id = 1"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void preservesEarlierVersionWhenAddingNewVersion() {
		insertTerms(1L, "TERMS_OF_SERVICE", "9.0", EFFECTIVE_AT, EFFECTIVE_AT, true, true);
		insertTerms(2L, "TERMS_OF_SERVICE", "10.0", EFFECTIVE_AT.plusDays(30), EFFECTIVE_AT, true, true);
		assertThat(jdbc.queryForList("SELECT version FROM fishingstory.terms ORDER BY effective_at", String.class))
				.containsExactly("9.0", "10.0");
	}

	private void insertTerms(Long id, String policy, String version, OffsetDateTime effectiveAt,
			OffsetDateTime publishedAt, Boolean consent, Boolean required) {
		jdbc.update("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, effective_at, published_at, requires_consent, is_required)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, policy, version, "정책 제목", "# 정책 전문", effectiveAt, publishedAt, consent, required);
	}
}
