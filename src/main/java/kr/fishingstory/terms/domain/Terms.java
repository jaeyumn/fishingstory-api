package kr.fishingstory.terms.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "terms", schema = "fishingstory")
public class Terms {
	@Id @Column(name = "terms_id") private Long id;
	@Column(name = "policy_type", nullable = false, length = 30) private String policyType;
	@Column(name = "version", nullable = false, length = 30) private String version;
	@Column(name = "title", nullable = false, length = 200) private String title;
	@Column(name = "content", nullable = false, columnDefinition = "text") private String content;
	@Column(name = "requires_consent", nullable = false) private boolean requiresConsent;
	@Column(name = "is_required", nullable = false) private boolean required;
	@Column(name = "effective_at", nullable = false) private Instant effectiveAt;
	@Column(name = "published_at") private Instant publishedAt;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected Terms() { }
	public Terms(long id, String type, String version, String title, String content, Instant effectiveAt, Instant now) {
		this.id = id;
		this.policyType = type;
		this.requiresConsent = "TERMS_OF_SERVICE".equals(type);
		this.required = this.requiresConsent;
		this.createdAt = now;
		edit(version, title, content, effectiveAt, now);
	}
	public boolean published() { return publishedAt != null; }
	public Instant updatedAt() { return updatedAt; }
	public void edit(String version, String title, String content, Instant effectiveAt, Instant now) {
		if (published()) throw new IllegalStateException("Published policy is immutable");
		this.version = version;
		this.title = title;
		this.content = content;
		this.effectiveAt = effectiveAt;
		this.updatedAt = now;
	}
	public void publish(boolean immediately, Instant now) {
		if (published()) return;
		if (immediately) this.effectiveAt = now;
		if (effectiveAt.isBefore(now)) throw new IllegalArgumentException("Effective time has passed");
		this.publishedAt = now;
		this.updatedAt = now;
	}
	public long id() { return id; }
	public boolean requiresConsent() { return requiresConsent; }
	public boolean required() { return required; }
}
