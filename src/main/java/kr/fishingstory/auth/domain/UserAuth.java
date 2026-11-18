package kr.fishingstory.auth.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_auth", schema = "fishingstory")
public class UserAuth {
	@Id @Column(name = "user_auth_id") private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(name = "provider", nullable = false, length = 20) private String provider;
	@Column(name = "email", length = 320) private String email;
	@Column(name = "provider_user_id", length = 255) private String providerUserId;
	@Column(name = "password_hash", columnDefinition = "text") private String passwordHash;
	@Column(name = "email_verified_at") private Instant emailVerifiedAt;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected UserAuth() { }
	public long userId() { return userId; }
	public String passwordHash() { return passwordHash; }
	public Instant emailVerifiedAt() { return emailVerifiedAt; }
	public void changePassword(String passwordHash, Instant now) {
		this.passwordHash = passwordHash;
		this.updatedAt = now;
	}
	public UserAuth(long id, long userId, String email, String passwordHash, Instant verifiedAt, Instant now) {
		this.id = id;
		this.userId = userId;
		this.provider = "EMAIL";
		this.email = email;
		this.passwordHash = passwordHash;
		this.emailVerifiedAt = verifiedAt;
		this.createdAt = now;
		this.updatedAt = now;
	}
}
