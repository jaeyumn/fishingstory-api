package kr.fishingstory.auth.infrastructure.password;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PasswordConfigurationTests {
	@Test
	void argon2idUsesIndependentSaltAndPreservesExactPassword() {
		var encoder = new PasswordConfiguration().passwordEncoder(19456, 2, 1);
		String password = " Password123 ";
		String first = encoder.encode(password);
		String second = encoder.encode(password);
		assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(second);
		assertThat(encoder.matches(password, first)).isTrue();
		assertThat(encoder.matches(password.strip(), first)).isFalse();
		assertThat(encoder.matches("Wrong1234", first)).isFalse();
	}
	@Test
	void refusesParametersBelowConfiguredSecurityFloor() {
		var config = new PasswordConfiguration();
		assertThatThrownBy(() -> config.passwordEncoder(16384, 2, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.passwordEncoder(19456, 1, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.passwordEncoder(19456, 2, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
