package kr.fishingstory.catchreport.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "catch_report_species", schema = "fishingstory")
public class CatchReportSpecies {
	@Id @Column(name = "report_species_id") private Long id;
	@Column(name = "report_id", nullable = false) private Long reportId;
	@Column(name = "species_id") private Long speciesId;
	@Column(name = "custom_name", length = 100) private String customName;
	@Column(name = "max_length_cm", precision = 5, scale = 1) private BigDecimal maxLengthCm;
	@Column(name = "max_weight_g", precision = 12, scale = 1) private BigDecimal maxWeightG;
	@Column(name = "sort_order", nullable = false) private int sortOrder;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	protected CatchReportSpecies() { }
	public Long speciesId() { return speciesId; }
	public String customName() { return customName; }
	public BigDecimal maxLengthCm() { return maxLengthCm; }
	public BigDecimal maxWeightG() { return maxWeightG; }
	public int sortOrder() { return sortOrder; }
	public CatchReportSpecies(long id, long reportId, long speciesId, BigDecimal maxLengthCm,
			int sortOrder, Instant createdAt) {
		this(id, reportId, speciesId, null, maxLengthCm, sortOrder, createdAt);
	}
	public CatchReportSpecies(long id, long reportId, Long speciesId, String customName, BigDecimal maxLengthCm,
			int sortOrder, Instant createdAt) {
		this(id, reportId, speciesId, customName, maxLengthCm, null, sortOrder, createdAt);
	}
	public CatchReportSpecies(long id, long reportId, Long speciesId, String customName, BigDecimal maxLengthCm,
			BigDecimal maxWeightG, int sortOrder, Instant createdAt) {
		this.maxWeightG = maxWeightG;
		this.id = id;
		this.reportId = reportId;
		this.speciesId = speciesId;
		this.customName = customName;
		this.maxLengthCm = maxLengthCm;
		this.sortOrder = sortOrder;
		this.createdAt = createdAt;
	}
}
