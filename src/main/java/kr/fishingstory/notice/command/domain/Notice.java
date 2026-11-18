package kr.fishingstory.notice.command.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notices", schema = "fishingstory")
public class Notice {
	@Id @Column(name = "notice_id") private Long id;
	@Column(nullable = false, length = 100) private String title;
	@Column(nullable = false, columnDefinition = "text") private String content;
	@Column(nullable = false) private boolean published;
	@Column(nullable = false) private boolean pinned;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected Notice() { }
	public Notice(long id, String title, String content, boolean published, boolean pinned, Instant now) {
		this.id = id;
		this.createdAt = now;
		change(title, content, published, pinned, now);
	}
	public void change(String title, String content, boolean published, boolean pinned, Instant now) {
		this.title = title;
		this.content = content;
		this.published = published;
		this.pinned = pinned;
		this.updatedAt = now;
	}
}
