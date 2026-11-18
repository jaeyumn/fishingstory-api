package kr.fishingstory.auth.infrastructure.password;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
	@Bean
	PasswordEncoder passwordEncoder(@Value("${app.password.memory-kib}") int memory,
			@Value("${app.password.iterations}") int iterations,
			@Value("${app.password.parallelism}") int parallelism) {
		if (memory < 19456 || iterations < 2 || parallelism < 1 || memory < 8L * parallelism) {
			throw new IllegalArgumentException("Invalid Argon2id parameters");
		}
		return new Argon2PasswordEncoder(16, 32, parallelism, memory, iterations);
	}
}
