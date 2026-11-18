package kr.fishingstory.catchreport.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_fish_collection", schema = "fishingstory")
public class UserFishCollection {
	@Id @Column(name = "collection_id") private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(name = "species_id", nullable = false) private Long speciesId;
	@Column(name = "max_length_cm", precision = 5, scale = 1) private BigDecimal maxLengthCm;
	@Column(name = "max_weight_g", precision = 12, scale = 1) private BigDecimal maxWeightG;
	@Column(name = "first_fishing_date", nullable = false) private LocalDate firstFishingDate;
	@Column(name = "last_fishing_date", nullable = false) private LocalDate lastFishingDate;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected UserFishCollection() { }
	public UserFishCollection(long id, long userId, long speciesId, BigDecimal maxLengthCm,
			LocalDate fishingDate, Instant now) {
		this.id = id;
		this.userId = userId;
		this.speciesId = speciesId;
		this.maxLengthCm = maxLengthCm;
		this.firstFishingDate = fishingDate;
		this.lastFishingDate = fishingDate;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public boolean recordWeight(BigDecimal weight) {
		boolean changed = weight != null && (maxWeightG == null || weight.compareTo(maxWeightG) > 0);
		if (changed) maxWeightG = weight;
		return changed;
	}

	public void replaceWeight(BigDecimal weight) { maxWeightG = weight; }

	public boolean record(BigDecimal length, LocalDate fishingDate, Instant now) {
		boolean maximumChanged = length != null
				&& (maxLengthCm == null || length.compareTo(maxLengthCm) > 0);
		if (maximumChanged) maxLengthCm = length;
		if (fishingDate.isBefore(firstFishingDate)) firstFishingDate = fishingDate;
		if (fishingDate.isAfter(lastFishingDate)) lastFishingDate = fishingDate;
		updatedAt = now;
		return maximumChanged;
	}

	public void replace(BigDecimal length, LocalDate firstDate, LocalDate lastDate, Instant now) {
		this.maxLengthCm = length;
		this.firstFishingDate = firstDate;
		this.lastFishingDate = lastDate;
		this.updatedAt = now;
	}
}
