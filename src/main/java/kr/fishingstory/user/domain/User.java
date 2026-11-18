package kr.fishingstory.user.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users", schema = "fishingstory")
public class User {
	@Id @Column(name = "user_id") private Long id;
	@Column(name = "nickname", nullable = false, length = 20) private String nickname;
	@Column(name = "nickname_key", nullable = false, length = 20) private String nicknameKey;
	@Column(name = "nickname_changed_at") private Instant nicknameChangedAt;
	@Column(name = "age_confirmed_at", nullable = false) private Instant ageConfirmedAt;
	@Column(name = "status", nullable = false, length = 30) private String status;
	@Column(name = "role", nullable = false, length = 20) private String role;
	@Column(name = "withdrawal_requested_at") private Instant withdrawalRequestedAt;
	@Column(name = "access_token_generation", nullable = false) private long accessTokenGeneration;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected User() { }
	public long id() { return id; }
	public String nickname() { return nickname; }
	public Instant nicknameChangedAt() { return nicknameChangedAt; }
	public String role() { return role; }
	public String status() { return status; }
	public Instant withdrawalRequestedAt() { return withdrawalRequestedAt; }
	public long accessTokenGeneration() { return accessTokenGeneration; }
	public void requestWithdrawal(Instant now) {
		if ("ACTIVE".equals(status)) {
			status = "WITHDRAW_PENDING";
			withdrawalRequestedAt = now;
			updatedAt = now;
		}
	}
	public void cancelWithdrawal(Instant now) {
		if (!"WITHDRAW_PENDING".equals(status) || withdrawalRequestedAt == null) {
			throw new IllegalStateException("Withdrawal is not pending");
		}
		// Keep pending-state retries valid, then revoke all previously issued Access on recovery.
		accessTokenGeneration = Math.incrementExact(accessTokenGeneration);
		status = "ACTIVE";
		withdrawalRequestedAt = null;
		updatedAt = now;
	}
	public void changeNickname(Nickname nickname, Instant now) {
		this.nickname = nickname.value();
		this.nicknameKey = nickname.key();
		this.nicknameChangedAt = now;
		this.updatedAt = now;
	}
	public User(long id, String nickname, String nicknameKey, Instant now) {
		this.id = id;
		this.nickname = nickname;
		this.nicknameKey = nicknameKey;
		this.nicknameChangedAt = now;
		this.ageConfirmedAt = now;
		this.status = "ACTIVE";
		this.role = "USER";
		this.createdAt = now;
		this.updatedAt = now;
	}
}
