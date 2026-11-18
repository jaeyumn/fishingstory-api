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
class UsersSchemaTests {

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clearUsers() {
		jdbc.update("DELETE FROM fishingstory.users");
	}

	@Test
	void createsUserWithDefaultsAndExplicitId() {
		insertUser(101L, "낚시 Fisher", "낚시 fisher");
		var user = jdbc.queryForMap("SELECT * FROM fishingstory.users WHERE user_id = 101");
		assertThat(user).containsEntry("status", "ACTIVE").containsEntry("role", "USER")
				.containsEntry("withdrawal_requested_at", null)
				.containsEntry("nickname_changed_at", null);
		assertThat(user.get("age_confirmed_at")).isNotNull();
		assertThat(user.get("created_at")).isNotNull();
		assertThat(user.get("updated_at")).isNotNull();
	}

	@Test
	void rejectsCaseInsensitiveNicknameDuplicates() {
		insertUser(101L, "Fisher", "fisher");
		assertThatThrownBy(() -> insertUser(102L, "FISHER", "fisher"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.users", Integer.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"가", "                     ", " 낚시", "낚시 ", "낚시  왕", "낚시\t왕",
			"낚시\n왕", "ㅋㅋ", "낚시!", "낚시🎣", "낚시\u00a0왕", "낚시", "abcdefghijklmnopqrstu"})
	void rejectsInvalidStoredNicknames(String nickname) {
		assertThatThrownBy(() -> insertUser(101L, nickname, nickname))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"Fisher", "someone", " fisher"})
	void rejectsNicknameKeysThatDoNotMatchDisplayName(String key) {
		assertThatThrownBy(() -> insertUser(101L, "Fisher", key))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"가힣", "AZ az 09", "abcdefghijklmnopqrst"})
	void acceptsNicknameBoundaries(String nickname) {
		insertUser(101L, nickname, nickname.toLowerCase(java.util.Locale.ROOT));
		assertThat(jdbc.queryForObject("SELECT nickname FROM fishingstory.users", String.class)).isEqualTo(nickname);
	}

	@Test
	void requiresIdAndAgeConfirmation() {
		assertThatThrownBy(() -> insertUser(null, "낚시왕", "낚시왕"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO fishingstory.users (user_id, nickname, nickname_key)
				VALUES (101, '낚시왕', '낚시왕')
				""" )).isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"CAPTAIN", "ROOT"})
	void rejectsUnsupportedRoles(String role) {
		insertUser(101L, "낚시왕", "낚시왕");
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.users SET role = ? WHERE user_id = 101", role))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void acceptsAdminRole() {
		insertUser(101L, "낚시왕", "낚시왕");
		assertThat(jdbc.update("UPDATE fishingstory.users SET role = 'ADMIN' WHERE user_id = 101")).isEqualTo(1);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"WITHDRAWN", "BLOCKED"})
	void rejectsUnsupportedStatuses(String status) {
		insertUser(101L, "낚시왕", "낚시왕");
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.users SET status = ? WHERE user_id = 101", status))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void requiresStatusAndWithdrawalTimeToChangeTogether() {
		insertUser(101L, "낚시왕", "낚시왕");
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.users SET status = 'WITHDRAW_PENDING'"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.users SET withdrawal_requested_at = CURRENT_TIMESTAMP"))
				.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("""
				UPDATE fishingstory.users
				SET status = 'WITHDRAW_PENDING', withdrawal_requested_at = CURRENT_TIMESTAMP
				WHERE user_id = 101
				""");
		assertThatThrownBy(() -> jdbc.update("UPDATE fishingstory.users SET status = 'ACTIVE'"))
				.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("UPDATE fishingstory.users SET status = 'ACTIVE', withdrawal_requested_at = NULL WHERE user_id = 101");
		assertThat(jdbc.queryForMap("SELECT status, withdrawal_requested_at FROM fishingstory.users"))
				.containsEntry("status", "ACTIVE").containsEntry("withdrawal_requested_at", null);
	}

	private void insertUser(Long id, String nickname, String key) {
		jdbc.update("""
				INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at)
				VALUES (?, ?, ?, ?)
				""", id, nickname, key, OffsetDateTime.parse("2026-09-12T00:00:00Z"));
	}
}
