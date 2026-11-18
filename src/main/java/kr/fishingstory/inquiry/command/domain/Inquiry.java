package kr.fishingstory.inquiry.command.domain;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "inquiries", schema = "fishingstory")
public class Inquiry {
	@Id @Column(name = "inquiry_id") private Long id;
	@Column(name = "user_id", nullable = false) private long userId;
	@Column(name = "request_key", nullable = false, length = 32) private String requestKey;
	@Column(nullable = false, length = 100) private String title;
	@Column(nullable = false, length = 20) private String status;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;
	protected Inquiry() { }
	public Inquiry(long id, long userId, String requestKey, String title, Instant now) {
		this.id = id; this.userId = userId; this.requestKey = requestKey; this.title = title;
		this.status = "OPEN"; this.createdAt = now; this.updatedAt = now;
	}
	public long id() { return id; }
	public long userId() { return userId; }
	public String title() { return title; }
	public boolean open() { return "OPEN".equals(status); }
	public void touch(Instant now) { updatedAt = now; }
	public void changeStatus(String status, Instant now) {
		if (!this.status.equals(status)) { this.status = status; touch(now); }
	}
}
