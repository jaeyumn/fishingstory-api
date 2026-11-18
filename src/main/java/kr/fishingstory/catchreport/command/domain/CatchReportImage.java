package kr.fishingstory.catchreport.command.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "catch_report_images", schema = "fishingstory")
public class CatchReportImage {
	@Id @Column(name = "image_id") private Long id;
	@Column(name = "report_id", nullable = false) private Long reportId;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(name = "request_key", length = 32) private String requestKey;
	@Column(name = "master_object_key", nullable = false, length = 300) private String masterKey;
	@Column(name = "thumbnail_object_key", nullable = false, length = 300) private String thumbnailKey;
	@Column(name = "status", nullable = false, length = 20) private String status;
	@Column(name = "temporary_cleaned", nullable = false) private boolean temporaryCleaned;
	@Column(name = "display_order") private Integer displayOrder;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "expires_at", nullable = false) private Instant expiresAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected CatchReportImage() { }
	public CatchReportImage(long id, long reportId, long userId, String requestKey, String masterKey, String thumbnailKey,
			Instant now, Instant expiresAt) {
		this.id = id;
		this.reportId = reportId;
		this.userId = userId;
		this.requestKey = requestKey;
		this.masterKey = masterKey;
		this.thumbnailKey = thumbnailKey;
		this.status = "PENDING";
		this.createdAt = now;
		this.expiresAt = expiresAt;
		this.updatedAt = now;
	}
	public long id() { return id; }
	public long reportId() { return reportId; }
	public long userId() { return userId; }
	public String masterKey() { return masterKey; }
	public String thumbnailKey() { return thumbnailKey; }
	public String status() { return status; }
	public Integer displayOrder() { return displayOrder; }
	public Instant createdAt() { return createdAt; }
	public Instant expiresAt() { return expiresAt; }
	public boolean temporaryCleaned() { return temporaryCleaned; }
	public void markTemporaryCleaned() { temporaryCleaned = true; }
	public void ready(int order, Instant now) {
		status = "READY";
		displayOrder = order;
		updatedAt = now;
	}
	public void markForDelete(Instant now) {
		status = "DELETE_PENDING";
		displayOrder = null;
		updatedAt = now;
	}
	public void reorder(int order, Instant now) {
		displayOrder = order;
		updatedAt = now;
	}
}
