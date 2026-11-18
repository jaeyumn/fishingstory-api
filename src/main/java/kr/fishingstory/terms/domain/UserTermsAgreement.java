package kr.fishingstory.terms.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_terms_agreement_history", schema = "fishingstory")
public class UserTermsAgreement {
	@Id @Column(name = "agreement_history_id") private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(name = "terms_id", nullable = false) private Long termsId;
	@Column(name = "action", nullable = false, length = 20) private String action;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	protected UserTermsAgreement() { }
	public UserTermsAgreement(long id, long userId, long termsId, Instant now) {
		this.id = id;
		this.userId = userId;
		this.termsId = termsId;
		this.action = "AGREED";
		this.createdAt = now;
	}
}
