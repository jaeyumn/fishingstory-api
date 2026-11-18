package kr.fishingstory.auth.infrastructure.jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtConfigurationTests {
	static final JwtConfiguration CONFIG = new JwtConfiguration();
	static KeyPair pair;
	static JwtEncoder encoder;
	static JwtDecoder decoder;
	@BeforeAll
	static void keys() {
		pair = JwtConfiguration.loadKeys("", "", true);
		encoder = CONFIG.jwtEncoder(pair);
		decoder = CONFIG.jwtDecoder(pair, "test-issuer", "test-app");
	}
	@Test
	void validRsaClaimsAndRoleAreAccepted() {
		var issuer = new AccessTokenIssuer(encoder, pair, "test-issuer", "test-app");
		var token = issuer.issue(123, "ADMIN", "test-session");
		var claims = decoder.decode(token.value());
		assertThat(claims.getSubject()).isEqualTo("123");
		assertThat(claims.getClaimAsString("role")).isEqualTo("ADMIN");
		assertThat(claims.getAudience()).containsExactly("test-app");
		assertThat(claims.getId()).isNotBlank();
		assertThat(claims.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", JwtConfiguration.keyId(pair));
		assertThat(token.toString()).doesNotContain(token.value());
	}
	@ParameterizedTest
	@ValueSource(strings = {"expired", "issuer", "audience", "subject", "role", "missing-exp", "missing-iat", "missing-sid", "missing-jti", "future-iat", "long-lifetime"})
	void rejectsInvalidClaims(String failure) {
		Instant now = Instant.now().minusSeconds(1);
		var claims = JwtClaimsSet.builder().issuer("test-issuer").audience(List.of("test-app"))
				.subject("123").claim("role", "USER").claim("sid", "session").id("jti").issuedAt(now).expiresAt(now.plusSeconds(900));
		switch (failure) {
			case "expired" -> claims.issuedAt(now.minusSeconds(1000)).expiresAt(now.minusSeconds(100));
			case "issuer" -> claims.issuer("other-issuer");
			case "audience" -> claims.audience(List.of("other-app"));
			case "subject" -> claims.subject("not-a-user-id");
			case "role" -> claims.claim("role", "OWNER");
			case "missing-exp" -> claims.claims(values -> values.remove("exp"));
			case "missing-iat" -> claims.claims(values -> values.remove("iat"));
			case "missing-sid" -> claims.claims(values -> values.remove("sid"));
			case "missing-jti" -> claims.claims(values -> values.remove("jti"));
			case "future-iat" -> claims.issuedAt(now.plusSeconds(120)).expiresAt(now.plusSeconds(900));
			case "long-lifetime" -> claims.expiresAt(now.plusSeconds(1800));
		}
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}
	@Test
	void rejectsOtherSigningKeyAndUnsignedToken() {
		var otherPair = JwtConfiguration.loadKeys("", "", true);
		var issuer = new AccessTokenIssuer(CONFIG.jwtEncoder(otherPair), otherPair, "test-issuer", "test-app");
		assertThatThrownBy(() -> decoder.decode(issuer.issue(123, "USER", "session").value())).isInstanceOf(JwtException.class);
		String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> decoder.decode(header + ".e30.")).isInstanceOf(JwtException.class);
	}
	@Test
	void loadsMatchingBase64PemKeysAndRefusesMissingMalformedOrMismatchedKeys() {
		String privateKey = base64Pem("PRIVATE KEY", pair.getPrivate().getEncoded());
		String publicKey = base64Pem("PUBLIC KEY", pair.getPublic().getEncoded());
		assertThat(JwtConfiguration.loadKeys(privateKey, publicKey, false).getPublic().getEncoded()).isEqualTo(pair.getPublic().getEncoded());
		assertThatThrownBy(() -> JwtConfiguration.loadKeys("", "", false)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> JwtConfiguration.loadKeys(privateKey, "", true)).isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining(privateKey).hasNoCause();
		assertThatThrownBy(() -> JwtConfiguration.loadKeys("not-base64", publicKey, true)).isInstanceOf(IllegalStateException.class);
		var otherPair = JwtConfiguration.loadKeys("", "", true);
		assertThatThrownBy(() -> JwtConfiguration.loadKeys(privateKey, base64Pem("PUBLIC KEY", otherPair.getPublic().getEncoded()), false))
				.isInstanceOf(IllegalStateException.class);
	}
	@Test
	void weakKeysAndProductionEphemeralFallbackAreRejected() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(1024);
		var weak = generator.generateKeyPair();
		assertThatThrownBy(() -> JwtConfiguration.loadKeys(base64Pem("PRIVATE KEY", weak.getPrivate().getEncoded()),
				base64Pem("PUBLIC KEY", weak.getPublic().getEncoded()), false)).isInstanceOf(IllegalStateException.class);
		Environment environment = mock(Environment.class);
		when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true); // dev + prod 모두 활성화된 경우
		assertThatThrownBy(() -> CONFIG.jwtKeyPair(environment, "", "", true)).isInstanceOf(IllegalStateException.class);
	}
	private static String base64Pem(String type, byte[] der) {
		String pem = "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
		return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
	}
}
