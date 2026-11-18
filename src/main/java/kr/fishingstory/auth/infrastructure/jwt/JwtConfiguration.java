package kr.fishingstory.auth.infrastructure.jwt;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
	@Bean
	KeyPair jwtKeyPair(Environment environment,
			@Value("${app.jwt.private-key-base64}") String privateKey,
			@Value("${app.jwt.public-key-base64}") String publicKey,
			@Value("${app.jwt.ephemeral-key}") boolean ephemeral) {
		boolean local = environment.acceptsProfiles(Profiles.of("dev", "test"))
				&& !environment.acceptsProfiles(Profiles.of("prod"));
		return loadKeys(privateKey, publicKey, ephemeral && local);
	}

	static KeyPair loadKeys(String privateValue, String publicValue, boolean allowEphemeral) {
		try {
			if (privateValue.isBlank() && publicValue.isBlank() && allowEphemeral) {
				var generator = KeyPairGenerator.getInstance("RSA");
				generator.initialize(2048);
				return generator.generateKeyPair();
			}
			var privateKey = RsaKeyConverters.pkcs8().convert(new ByteArrayInputStream(Base64.getDecoder().decode(privateValue)));
			var publicKey = RsaKeyConverters.x509().convert(new ByteArrayInputStream(Base64.getDecoder().decode(publicValue)));
			if (privateKey == null || publicKey == null || publicKey.getModulus().bitLength() < 2048
					|| !privateKey.getModulus().equals(publicKey.getModulus())) throw new IllegalArgumentException();
			var signature = Signature.getInstance("SHA256withRSA");
			byte[] probe = "fishingstory-key-check".getBytes(StandardCharsets.US_ASCII);
			signature.initSign(privateKey);
			signature.update(probe);
			byte[] signed = signature.sign();
			signature.initVerify(publicKey);
			signature.update(probe);
			if (!signature.verify(signed)) throw new IllegalArgumentException();
			return new KeyPair(publicKey, privateKey);
		} catch (Exception failure) {
			// 변환 예외에 키 원문이 섞일 수 있어 원인 예외를 노출하지 않는다.
			throw new IllegalStateException("Valid matching RSA keys of at least 2048 bits are required");
		}
	}

	@Bean
	JwtEncoder jwtEncoder(KeyPair jwtKeyPair) {
		var key = new RSAKey.Builder((RSAPublicKey) jwtKeyPair.getPublic())
				.privateKey((RSAPrivateKey) jwtKeyPair.getPrivate()).keyID(keyId(jwtKeyPair))
				.algorithm(JWSAlgorithm.RS256).build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
	}

	@Bean
	JwtDecoder jwtDecoder(KeyPair jwtKeyPair, @Value("${app.jwt.issuer}") String issuer,
			@Value("${app.jwt.audience}") String audience) {
		if (issuer.isBlank() || audience.isBlank()) throw new IllegalArgumentException("JWT issuer and audience are required");
		var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) jwtKeyPair.getPublic())
				.signatureAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
				new JwtIssuerValidator(issuer), new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience)),
				JwtConfiguration::validateClaims));
		return decoder;
	}

	private static OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
		try {
			boolean valid = Long.parseLong(jwt.getSubject()) > 0 && Set.of("USER", "ADMIN").contains(jwt.getClaimAsString("role"))
					&& jwt.getId() != null && !jwt.getId().isBlank() && jwt.getClaimAsString("sid") != null
					&& !jwt.getClaimAsString("sid").isBlank() && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
					&& !jwt.getIssuedAt().isAfter(Instant.now()) && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
					&& !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(900));
			if (valid) return OAuth2TokenValidatorResult.success();
		} catch (RuntimeException invalid) { /* 거절된 토큰 원문·claim을 기록하지 않는다. */ }
		return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token claims", null));
	}

	static String keyId(KeyPair keyPair) {
		try {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(keyPair.getPublic().getEncoded()));
		} catch (Exception impossible) { throw new IllegalStateException("Unable to identify signing key"); }
	}
}
