package kr.fishingstory.auth.application;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import kr.fishingstory.user.domain.Nickname;

import static org.assertj.core.api.Assertions.*;

class EmailSignupCommandTests {
	static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	static final Validator VALIDATOR = FACTORY.getValidator();
	static final String TOKEN = "A".repeat(43);
	@AfterAll static void close() { FACTORY.close(); }

	@Test
	void normalizesEmailAndNicknameButNeverPassword() {
		String password = " Password1 ";
		var command = new EmailSignupCommand(" Fi.Sher+Boat@Example.COM ", TOKEN, password, password,
				"  낚시  Fisher  ", true, 12L);
		assertThat(VALIDATOR.validate(command)).isEmpty();
		assertThat(command.email()).isEqualTo("fi.sher+boat@example.com");
		assertThat(command.nickname()).isEqualTo("낚시 Fisher");
		assertThat(Nickname.from(command.nickname()).key()).isEqualTo("낚시 fisher");
		assertThat(command.password()).isEqualTo(password);
		assertThat(command.toString()).doesNotContain(password, TOKEN, command.email());
	}
	@Test
	void passwordSupportsEightThrough128CodePointsWithLettersAndDigits() {
		for (String password : new String[] {"Abcdefg1", "a1" + "b".repeat(126), "a1" + "🐟".repeat(126)}) {
			assertThat(VALIDATOR.validate(command(password, "Fisher", true, 12L))).isEmpty();
		}
		for (String password : new String[] {"Abcdef1", "abcdefgh", "12345678", "Password1\uD800", "a1" + "b".repeat(127), "a1" + "🐟".repeat(127)}) {
			assertThat(VALIDATOR.validate(command(password, "Fisher", true, 12L))).isNotEmpty();
		}
	}
	@Test
	void requiresAgeConsentAndExactPasswordConfirmation() {
		assertThat(VALIDATOR.validate(command("Password1", "Fisher", false, 12L))).isNotEmpty();
		assertThat(VALIDATOR.validate(command("Password1", "Fisher", null, 12L))).isNotEmpty();
		assertThat(VALIDATOR.validate(command("Password1", "Fisher", true, null))).isNotEmpty();
		assertThat(VALIDATOR.validate(command("Password1", "Fisher", true, 0L))).isNotEmpty();
		var mismatch = new EmailSignupCommand("fisher@example.com", TOKEN, "Password1 ", "Password1", "Fisher", true, 12L);
		assertThat(VALIDATOR.validate(mismatch)).isNotEmpty();
	}
	@Test
	void nicknameEnforcesNormalizedLengthAndAllowedCharacters() {
		for (String nickname : new String[] {"가나", "a".repeat(20), " 낚시  왕 "}) {
			assertThat(VALIDATOR.validate(command("Password1", nickname, true, 12L))).isEmpty();
		}
		for (String nickname : new String[] {"가", "a".repeat(21), "ㅋㅋ", "Fisher🐟", " A\tB ", "A\nB", "   "}) {
			assertThat(VALIDATOR.validate(command("Password1", nickname, true, 12L))).isNotEmpty();
		}
	}
	private EmailSignupCommand command(String password, String nickname, Boolean age, Long terms) {
		return new EmailSignupCommand("fisher@example.com", TOKEN, password, password, nickname, age, terms);
	}
}
