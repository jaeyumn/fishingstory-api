package kr.fishingstory.auth.infrastructure.jwt;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Component
public class AccessTokenIssuer {
	public static final String GENERATION_CLAIM = "access_gen";
	private final JwtEncoder encoder;
	private final String keyId;
	private final String issuer;
	private final String audience;
	public AccessTokenIssuer(JwtEncoder encoder, KeyPair jwtKeyPair, @Value("${app.jwt.issuer}") String issuer,
			@Value("${app.jwt.audience}") String audience) {
		this.encoder = encoder;
		this.keyId = JwtConfiguration.keyId(jwtKeyPair);
		this.issuer = issuer;
		this.audience = audience;
	}
	public Issued issue(long userId, String role, String sessionId) {
		return issue(userId, role, sessionId, 0);
	}
	public Issued issue(long userId, String role, String sessionId, long generation) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant expiry = now.plusSeconds(900);
		var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(Long.toString(userId))
				.claim("role", role).claim("sid", sessionId).claim(GENERATION_CLAIM, generation)
				.id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(expiry).build();
		var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();
		try { return new Issued(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiry); }
		catch (JwtEncodingException unavailable) { throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE); }
	}
	public record Issued(String value, Instant expiresAt) {
		@Override public String toString() { return "Issued[REDACTED]"; }
	}
}
