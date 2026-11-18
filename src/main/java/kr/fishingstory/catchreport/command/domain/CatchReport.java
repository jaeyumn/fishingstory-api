package kr.fishingstory.catchreport.command.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catch_reports", schema = "fishingstory")
public class CatchReport {
	@Id @Column(name = "report_id") private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(name = "catch_status", nullable = false, length = 20) private String catchStatus;
	@Column(name = "water_type", nullable = false, length = 20) private String waterType;
	@Column(name = "fishing_date", nullable = false) private LocalDate fishingDate;
	// Bind LocalTime directly: java.sql.Time would round fractional seconds to milliseconds.
	@JdbcTypeCode(SqlTypes.LOCAL_TIME)
	@Column(name = "fishing_time") private LocalTime fishingTime;
	@Column(name = "memo", length = 2000) private String memo;
	@Column(name = "visibility", nullable = false, length = 20) private String visibility;
	@Column(name = "location_visibility", nullable = false, length = 20) private String locationVisibility;
	@Column(name = "request_key", length = 32) private String requestKey;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected CatchReport() { }

	public CatchReport(long id, long userId, String requestKey, String catchStatus, String waterType,
			LocalDate fishingDate, LocalTime fishingTime, String memo, Instant now) {
		this.id = id;
		this.userId = userId;
		this.requestKey = requestKey;
		this.catchStatus = catchStatus;
		this.waterType = waterType;
		this.fishingDate = fishingDate;
		this.fishingTime = fishingTime;
		this.memo = memo;
		this.visibility = "PRIVATE";
		this.locationVisibility = "PRIVATE";
		this.createdAt = now;
		this.updatedAt = now;
	}

	public long id() { return id; }
	public String catchStatus() { return catchStatus; }
	public String visibility() { return visibility; }
	public String locationVisibility() { return locationVisibility; }
	public Instant createdAt() { return createdAt; }

	public boolean hasContent(String catchStatus, String waterType, LocalDate fishingDate,
			LocalTime fishingTime, String memo) {
		return this.catchStatus.equals(catchStatus) && this.waterType.equals(waterType)
				&& this.fishingDate.equals(fishingDate) && java.util.Objects.equals(this.fishingTime, fishingTime)
				&& java.util.Objects.equals(this.memo, memo);
	}

	public void update(String catchStatus, String waterType, LocalDate fishingDate,
			LocalTime fishingTime, String memo, Instant now) {
		this.catchStatus = catchStatus;
		this.waterType = waterType;
		this.fishingDate = fishingDate;
		this.fishingTime = fishingTime;
		this.memo = memo;
		this.updatedAt = now;
	}

	public void changeVisibility(String visibility, Instant now) {
		if (!"PRIVATE".equals(visibility) && !"PUBLIC".equals(visibility)) {
			throw new IllegalArgumentException("Invalid report visibility");
		}
		if (this.visibility.equals(visibility)) return;
		this.visibility = visibility;
		this.updatedAt = now;
	}
}
