package kr.fishingstory.auth.application;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationCodeGenerator {
	private final SecureRandom random = new SecureRandom();

	public String generate() {
		return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
	}
}
