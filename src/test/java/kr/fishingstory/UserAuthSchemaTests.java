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
class UserAuthSchemaTests {

	// Schema tests use a placeholder, not a password hash implementation.
	private static final String HASH = "test-only-hash-placeholder";
	private static final OffsetDateTime VERIFIED_AT = OffsetDateTime.parse("2026-09-12T00:00:00Z");

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void prepareUsers() {
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("""
				INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at)
				SELECT id, 'fisher' || id, 'fisher' || id, CURRENT_TIMESTAMP
				FROM generate_series(1, 10) AS id
				""");
	}

	@Test
	void storesEmailCredentialsAndTimestampDefaults() {
		insertAuth(101L, 1L, "EMAIL", "fisher@example.com", null, HASH, VERIFIED_AT);
		var auth = jdbc.queryForMap("SELECT * FROM fishingstory.user_auth WHERE user_auth_id = 101");
		assertThat(auth).containsEntry("email", "fisher@example.com")
				.containsEntry("password_hash", HASH).containsEntry("provider_user_id", null);
		assertThat(auth.get("created_at")).isNotNull();
		assertThat(auth.get("updated_at")).isNotNull();
		assertThat(jdbc.queryForObject("SELECT email_verified_at FROM fishingstory.user_auth", OffsetDateTime.class))
				.isEqualTo(VERIFIED_AT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"KAKAO", "NAVER", "GOOGLE"})
	void acceptsSocialAccountsWithOrWithoutEmail(String provider) {
		insertAuth(101L, 1L, provider, null, "Subject-A", null, null);
		insertAuth(102L, 2L, provider, "fisher@example.com", "Subject-B", null, null);
		assertThat(jdbc.queryForList("SELECT provider_user_id FROM fishingstory.user_auth ORDER BY user_auth_id", String.class))
				.containsExactly("Subject-A", "Subject-B");
	}

	@ParameterizedTest
	@MethodSource("invalidCredentials")
	void rejectsInvalidCredentialCombinations(String provider, String email, String subject,
			String hash, OffsetDateTime verifiedAt) {
		assertThatThrownBy(() -> insertAuth(101L, 1L, provider, email, subject, hash, verifiedAt))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_user_auth_credentials");
	}

	static Stream<Arguments> invalidCredentials() {
		var emailCases = Stream.of(
				Arguments.of("EMAIL", null, null, HASH, VERIFIED_AT),
				Arguments.of("EMAIL", "fisher@example.com", null, null, VERIFIED_AT),
				Arguments.of("EMAIL", "fisher@example.com", null, "", VERIFIED_AT),
				Arguments.of("EMAIL", "fisher@example.com", null, " \t\n", VERIFIED_AT),
				Arguments.of("EMAIL", "fisher@example.com", null, HASH, null),
				Arguments.of("EMAIL", "fisher@example.com", "subject", HASH, VERIFIED_AT));
		var socialCases = Stream.of("KAKAO", "NAVER", "GOOGLE").flatMap(provider -> Stream.of(
				Arguments.of(provider, null, null, null, null),
				Arguments.of(provider, null, "", null, null),
				Arguments.of(provider, null, " \t\n", null, null),
				Arguments.of(provider, null, "subject", HASH, null),
				Arguments.of(provider, null, "subject", null, VERIFIED_AT)));
		return Stream.concat(emailCases, socialCases);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t\n", "Fisher@example.com", "fisher@Example.com",
			" fisher@example.com", "fisher@example.com ", "\tfisher@example.com", "fisher@example.com\n"})
	void rejectsUnnormalizedEmailForBothAccountTypes(String email) {
		assertThatThrownBy(() -> insertAuth(101L, 1L, "EMAIL", email, null, HASH, VERIFIED_AT))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_user_auth_email");
		assertThatThrownBy(() -> insertAuth(102L, 2L, "GOOGLE", email, "subject", null, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_user_auth_email");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"APPLE", "email", "UNKNOWN"})
	void rejectsUnsupportedProviders(String provider) {
		assertThatThrownBy(() -> insertAuth(101L, 1L, provider, null, "subject", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void requiresExplicitIds() {
		assertThatThrownBy(() -> insertAuth(null, 1L, "GOOGLE", null, "subject", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAuth(101L, null, "GOOGLE", null, "subject", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsOnlyOneLoginMethodPerUser() {
		insertAuth(101L, 1L, "EMAIL", "fisher@example.com", null, HASH, VERIFIED_AT);
		assertThatThrownBy(() -> insertAuth(102L, 1L, "GOOGLE", null, "subject", null, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_user_auth_user_id");
	}

	@Test
	void enforcesEmailUniquenessOnlyForEmailAccounts() {
		insertAuth(101L, 1L, "EMAIL", "fisher@example.com", null, HASH, VERIFIED_AT);
		insertAuth(102L, 2L, "GOOGLE", "fisher@example.com", "subject-a", null, null);
		insertAuth(103L, 3L, "GOOGLE", "fisher@example.com", "subject-b", null, null);
		insertAuth(104L, 4L, "KAKAO", "fisher@example.com", "subject-a", null, null);
		assertThatThrownBy(() -> insertAuth(105L, 5L, "EMAIL", "fisher@example.com", null, HASH, VERIFIED_AT))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_user_auth_email");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_auth", Integer.class)).isEqualTo(4);
	}

	@Test
	void preservesDotsAndPlusInEmailAddresses() {
		insertAuth(101L, 1L, "EMAIL", "fisher@example.com", null, HASH, VERIFIED_AT);
		insertAuth(102L, 2L, "EMAIL", "fi.sher@example.com", null, HASH, VERIFIED_AT);
		insertAuth(103L, 3L, "EMAIL", "fisher+fishing@example.com", null, HASH, VERIFIED_AT);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_auth", Integer.class)).isEqualTo(3);
	}

	@Test
	void scopesSocialIdentityToProviderAndPreservesCase() {
		insertAuth(101L, 1L, "GOOGLE", null, "Subject-A", null, null);
		insertAuth(102L, 2L, "KAKAO", null, "Subject-A", null, null);
		insertAuth(103L, 3L, "GOOGLE", null, "subject-a", null, null);
		assertThatThrownBy(() -> insertAuth(104L, 4L, "GOOGLE", "other@example.com", "Subject-A", null, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_user_auth_social_identity");
	}

	@Test
	void allowsSocialEmailChangesWithoutChangingIdentity() {
		insertAuth(101L, 1L, "NAVER", "old@example.com", "subject", null, null);
		jdbc.update("UPDATE fishingstory.user_auth SET email = 'new@example.com' WHERE user_auth_id = 101");
		assertThat(jdbc.queryForObject("SELECT email FROM fishingstory.user_auth", String.class)).isEqualTo("new@example.com");
		jdbc.update("UPDATE fishingstory.user_auth SET email = NULL WHERE user_auth_id = 101");
		assertThat(jdbc.queryForMap("SELECT user_id, provider, provider_user_id, email, email_verified_at FROM fishingstory.user_auth"))
				.containsEntry("user_id", 1L).containsEntry("provider", "NAVER")
				.containsEntry("provider_user_id", "subject").containsEntry("email", null)
				.containsEntry("email_verified_at", null);
	}

	private void insertAuth(Long id, Long userId, String provider, String email, String subject,
			String hash, OffsetDateTime verifiedAt) {
		jdbc.update("""
				INSERT INTO fishingstory.user_auth
				(user_auth_id, user_id, provider, email, provider_user_id, password_hash, email_verified_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", id, userId, provider, email, subject, hash, verifiedAt);
	}
}
