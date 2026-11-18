package kr.fishingstory.catchreport.command.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;

public record CreateCatchReportCommand(
		String catchStatus,
		String waterType,
		LocalDate fishingDate,
		LocalTime fishingTime,
		String memo,
		List<SpeciesSelection> species) {

	public CreateCatchReportCommand {
		// PostgreSQL TIME stores microseconds. Normalize before both persistence and replay comparison.
		fishingTime = fishingTime == null ? null : fishingTime.truncatedTo(ChronoUnit.MICROS);
		species = species == null ? List.of() : List.copyOf(species);
		if (!("NO_CATCH".equals(catchStatus) || "CAUGHT".equals(catchStatus))
				|| !("SEA".equals(waterType) || "FRESHWATER".equals(waterType))
				|| fishingDate == null
				|| (memo != null && (memo.codePointCount(0, memo.length()) > 2000 || memo.indexOf('\0') >= 0))
				|| ("CAUGHT".equals(catchStatus) && species.isEmpty())
				|| ("NO_CATCH".equals(catchStatus) && !species.isEmpty())) {
			throw new IllegalArgumentException("Invalid catch report");
		}
		var ids = new HashSet<Long>();
		var names = new HashSet<String>();
		for (SpeciesSelection item : species) {
			if (item == null
					|| (item.speciesId() == null ? !names.add(item.customName()) : !ids.add(item.speciesId()))
					|| !validLength(item.maxLengthCm()) || !validWeight(item.maxWeightG())) throw new IllegalArgumentException("Invalid species");
		}
	}

	private static boolean validLength(BigDecimal value) {
		if (value == null) return true;
		return value.compareTo(BigDecimal.ZERO) > 0
				&& value.compareTo(new BigDecimal("1000.0")) <= 0
				&& value.stripTrailingZeros().scale() <= 1;
	}

	// Up to 2,000,000 g (2 t), matching the V13 CHECK.
	private static boolean validWeight(BigDecimal value) {
		return value == null || (value.signum() > 0
				&& value.compareTo(new BigDecimal("2000000.0")) <= 0
				&& value.stripTrailingZeros().scale() <= 1);
	}

	public record SpeciesSelection(Long speciesId, String customName, BigDecimal maxLengthCm, BigDecimal maxWeightG) {
		public SpeciesSelection(Long speciesId, String customName, BigDecimal maxLengthCm) {
			this(speciesId, customName, maxLengthCm, null);
		}
		public SpeciesSelection(long speciesId, BigDecimal maxLengthCm) {
			this(speciesId, null, maxLengthCm);
		}

		public SpeciesSelection {
			if (customName != null) customName = Normalizer.normalize(customName, Normalizer.Form.NFC).strip();
			if ((speciesId == null) == (customName == null)
					|| (speciesId != null && speciesId <= 0)
					|| (customName != null && (customName.isBlank()
							|| customName.codePointCount(0, customName.length()) > 100
							|| customName.codePoints().anyMatch(Character::isISOControl)))) {
				throw new IllegalArgumentException("Invalid species source");
			}
		}
	}
}
