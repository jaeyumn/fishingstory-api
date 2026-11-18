package kr.fishingstory.conditions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import static kr.fishingstory.conditions.FishingLocations.MulttaeStyle.EIGHT;
import static kr.fishingstory.conditions.FishingLocations.MulttaeStyle.SEVEN;
import static org.assertj.core.api.Assertions.*;

class AlmanacCalculatorTests {
	@Test void newMoonMatchesTheMeeusWorkedExample() {
		// Astronomical Algorithms, example 49.a: k = -283 is 1977-02-18 03:37:42 TD; the code returns UT (TD − 69 s).
		var expected = Instant.parse("1977-02-18T03:37:42Z").minusSeconds(69);
		assertThat(Duration.between(expected, AlmanacCalculator.newMoon(-283)).abs()).isLessThan(Duration.ofSeconds(10));
	}
	@Test void lunarNewYearAndChuseokFallOnTheirKoreanDays() {
		for (var seollal : new String[] {"2023-01-22", "2024-02-10", "2025-01-29", "2026-02-17"}) {
			var day = LocalDate.parse(seollal);
			assertThat(AlmanacCalculator.lunarDay(day)).as(seollal).isEqualTo(1);
			assertThat(AlmanacCalculator.lunarDay(day.minusDays(1))).as(seollal).isIn(29, 30);
		}
		for (var chuseok : new String[] {"2023-09-29", "2024-09-17", "2025-10-06", "2026-09-25"}) {
			assertThat(AlmanacCalculator.lunarDay(LocalDate.parse(chuseok))).as(chuseok).isEqualTo(15);
		}
	}
	@Test void koreanMidnightDecidesTheFirstLunarDay() {
		// The new moon of 2027-02-06 15:5x UT is still 6 February in China but 7 February in Korea (설날).
		assertThat(AlmanacCalculator.lunarDay(LocalDate.of(2027, 2, 6))).isNotEqualTo(1);
		assertThat(AlmanacCalculator.lunarDay(LocalDate.of(2027, 2, 7))).isEqualTo(1);
		// 2026-10-10 15:5x UT is 11 October in Korea, so 24 October is the 14th.
		assertThat(AlmanacCalculator.lunarDay(LocalDate.of(2026, 10, 11))).isEqualTo(1);
		assertThat(AlmanacCalculator.lunarDay(LocalDate.of(2026, 10, 24))).isEqualTo(14);
	}
	@Test void westCoastUsesSevenAndOthersEightMulttaeTables() {
		assertThat(AlmanacCalculator.multtae(1, SEVEN)).isEqualTo("7물");
		assertThat(AlmanacCalculator.multtae(7, SEVEN)).isEqualTo("13물");
		assertThat(AlmanacCalculator.multtae(8, SEVEN)).isEqualTo("조금");
		assertThat(AlmanacCalculator.multtae(9, SEVEN)).isEqualTo("무시");
		assertThat(AlmanacCalculator.multtae(10, SEVEN)).isEqualTo("1물");
		assertThat(AlmanacCalculator.multtae(16, SEVEN)).isEqualTo("7물");
		assertThat(AlmanacCalculator.multtae(24, SEVEN)).isEqualTo("무시");
		assertThat(AlmanacCalculator.multtae(30, SEVEN)).isEqualTo("6물");
		assertThat(AlmanacCalculator.multtae(1, EIGHT)).isEqualTo("8물");
		assertThat(AlmanacCalculator.multtae(7, EIGHT)).isEqualTo("14물");
		assertThat(AlmanacCalculator.multtae(8, EIGHT)).isEqualTo("조금");
		assertThat(AlmanacCalculator.multtae(9, EIGHT)).isEqualTo("1물");
		assertThat(AlmanacCalculator.multtae(24, EIGHT)).isEqualTo("1물");
		assertThat(AlmanacCalculator.multtae(30, EIGHT)).isEqualTo("7물");
	}
	@Test void sunriseAndSunsetMatchPublishedSeoulTimesWithinTwoMinutes() {
		// KASI 출몰시각 for Seoul: 2024-06-21 05:11/19:57, 2024-12-21 07:43/17:17.
		assertNear(AlmanacCalculator.sunEvent(LocalDate.of(2024, 6, 21), 37.57, 126.98, true), "05:11");
		assertNear(AlmanacCalculator.sunEvent(LocalDate.of(2024, 6, 21), 37.57, 126.98, false), "19:57");
		assertNear(AlmanacCalculator.sunEvent(LocalDate.of(2024, 12, 21), 37.57, 126.98, true), "07:43");
		assertNear(AlmanacCalculator.sunEvent(LocalDate.of(2024, 12, 21), 37.57, 126.98, false), "17:17");
	}
	@Test void almanacOmitsMulttaeForFreshwaterButKeepsTheSun() {
		var day = LocalDate.of(2026, 10, 24);
		var incheon = AlmanacCalculator.of(FishingLocations.find("incheon"), day);
		assertThat(incheon.lunarDay()).isEqualTo(14);
		assertThat(incheon.multtae()).isEqualTo("5물");
		assertThat(incheon.multtaeStyle()).isEqualTo("7물때식");
		assertThat(AlmanacCalculator.of(FishingLocations.find("busan"), day).multtae()).isEqualTo("6물");
		var seoul = AlmanacCalculator.of(FishingLocations.find("seoul"), day);
		assertThat(seoul.multtae()).isNull();
		assertThat(seoul.multtaeStyle()).isNull();
		assertThat(seoul.sunrise()).matches("0[67]:[0-5][0-9]");
		assertThat(LocalTime.parse(seoul.sunset())).isAfter(LocalTime.parse(seoul.sunrise()));
	}
	private static void assertNear(String actual, String expected) {
		assertThat(actual).isNotNull();
		var difference = Duration.between(LocalTime.parse(expected), LocalTime.parse(actual)).abs();
		assertThat(difference).as(actual + " vs " + expected).isLessThanOrEqualTo(Duration.ofMinutes(2));
	}
}
