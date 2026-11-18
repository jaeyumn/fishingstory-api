package kr.fishingstory.catchreport.command.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "fish_species", schema = "fishingstory")
public class FishSpecies {
	@Id @Column(name = "species_id") private Long id;
	@Column(name = "name", nullable = false, length = 100) private String name;
	@Column(name = "water_type", nullable = false, length = 20) private String waterType;
	@Column(name = "active", nullable = false) private boolean active;

	protected FishSpecies() { }
	public long id() { return id; }
	public String name() { return name; }
	public String waterType() { return waterType; }
	public boolean supports(String reportWaterType) {
		return active && matchesWater(reportWaterType);
	}
	public boolean matchesWater(String reportWaterType) {
		return waterType.equals(reportWaterType) || "BOTH".equals(waterType);
	}
}
