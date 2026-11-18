package kr.fishingstory.conditions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static java.lang.Math.*;

/**
 * Lunar day, 물때 and sunrise/sunset computed on the server: no provider call, quota or cache.
 * New moons follow Meeus, Astronomical Algorithms ch. 49; the sun follows the NOAA solar calculator.
 */
final class AlmanacCalculator {
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter HOURS = DateTimeFormatter.ofPattern("HH:mm");
	private static final double UNIX_EPOCH_JD = 2440587.5;
	private static final double SYNODIC_MONTH = 29.530588861;
	// Meeus gives Dynamical Time. TT − UT is about 69 s in the 2020s; this constant errs by seconds,
	// so only a new moon within about a minute of Korean midnight could fall on the wrong date.
	private static final double DELTA_T_DAYS = 69.0 / 86_400;
	private static final List<String> SEVEN = List.of("7물", "8물", "9물", "10물", "11물", "12물", "13물", "조금", "무시",
			"1물", "2물", "3물", "4물", "5물", "6물");
	private static final List<String> EIGHT = List.of("8물", "9물", "10물", "11물", "12물", "13물", "14물", "조금",
			"1물", "2물", "3물", "4물", "5물", "6물", "7물");
	private static final double[] PLANETARY_COEFFICIENTS = {0.000325, 0.000165, 0.000164, 0.000126, 0.000110, 0.000062,
			0.000060, 0.000056, 0.000047, 0.000042, 0.000040, 0.000037, 0.000035, 0.000023};
	private AlmanacCalculator() { }

	static FishingConditions.Almanac of(FishingLocations.Location place, LocalDate date) {
		int day = lunarDay(date);
		var style = place.multtaeStyle();
		return new FishingConditions.Almanac(day, style == null ? null : multtae(day, style),
				style == null ? null : style == FishingLocations.MulttaeStyle.SEVEN ? "7물때식" : "8물때식",
				sunEvent(date, place.latitude(), place.longitude(), true), sunEvent(date, place.latitude(), place.longitude(), false),
				place.name() + " 대표 좌표 천문 계산");
	}

	/** Day of the Korean lunar month (1–30): the Korean date holding the latest new moon is day 1. */
	static int lunarDay(LocalDate date) {
		var end = date.plusDays(1).atStartOfDay(KOREA).toInstant();
		double julian = end.toEpochMilli() / 86_400_000.0 + UNIX_EPOCH_JD;
		// True new moons stay within about 0.6 days of the mean ones, so three candidates suffice.
		long k = (long) floor((julian - 2451550.09766) / SYNODIC_MONTH) + 1;
		for (int tries = 0; tries < 3 && !newMoon(k).isBefore(end); tries++) k--;
		var first = LocalDate.ofInstant(newMoon(k), KOREA);
		long day = ChronoUnit.DAYS.between(first, date) + 1;
		if (day < 1 || day > 30) throw new IllegalStateException("lunar day out of range");
		return (int) day;
	}
	static String multtae(int lunarDay, FishingLocations.MulttaeStyle style) {
		return (style == FishingLocations.MulttaeStyle.SEVEN ? SEVEN : EIGHT).get((lunarDay - 1) % 15);
	}

	/** Instant (UT) of new moon number k, k = 0 being 2000-01-06. */
	static Instant newMoon(long k) {
		double t = k / 1236.85, t2 = t * t, t3 = t2 * t, t4 = t3 * t;
		double jde = 2451550.09766 + SYNODIC_MONTH * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4;
		double e = 1 - 0.002516 * t - 0.0000074 * t2;
		double m = toRadians(2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3);
		double mp = toRadians(201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4);
		double f = toRadians(160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4);
		double omega = toRadians(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3);
		jde += -0.40720 * sin(mp) + 0.17241 * e * sin(m) + 0.01608 * sin(2 * mp) + 0.01039 * sin(2 * f)
				+ 0.00739 * e * sin(mp - m) - 0.00514 * e * sin(mp + m) + 0.00208 * e * e * sin(2 * m)
				- 0.00111 * sin(mp - 2 * f) - 0.00057 * sin(mp + 2 * f) + 0.00056 * e * sin(2 * mp + m)
				- 0.00042 * sin(3 * mp) + 0.00042 * e * sin(m + 2 * f) + 0.00038 * e * sin(m - 2 * f)
				- 0.00024 * e * sin(2 * mp - m) - 0.00017 * sin(omega) - 0.00007 * sin(mp + 2 * m)
				+ 0.00004 * sin(2 * mp - 2 * f) + 0.00004 * sin(3 * m) + 0.00003 * sin(mp + m - 2 * f)
				+ 0.00003 * sin(2 * mp + 2 * f) - 0.00003 * sin(mp + m + 2 * f) + 0.00003 * sin(mp - m + 2 * f)
				- 0.00002 * sin(mp - m - 2 * f) - 0.00002 * sin(3 * mp + m) + 0.00002 * sin(4 * mp);
		double[] planetary = {299.77 + 0.107408 * k - 0.009173 * t2, 251.88 + 0.016321 * k, 251.83 + 26.651886 * k,
				349.42 + 36.412478 * k, 84.66 + 18.206239 * k, 141.74 + 53.303771 * k, 207.14 + 2.453732 * k,
				154.84 + 7.306860 * k, 34.52 + 27.261239 * k, 207.19 + 0.121824 * k, 291.34 + 1.844379 * k,
				161.72 + 24.198154 * k, 239.56 + 25.513099 * k, 331.55 + 3.592518 * k};
		for (int i = 0; i < planetary.length; i++) jde += PLANETARY_COEFFICIENTS[i] * sin(toRadians(planetary[i]));
		return Instant.ofEpochMilli(round((jde - DELTA_T_DAYS - UNIX_EPOCH_JD) * 86_400_000));
	}

	/** Korean "HH:mm" of sunrise or sunset (upper limb, standard refraction), or null if the sun stays up or down. */
	static String sunEvent(LocalDate date, double latitude, double longitude, boolean rise) {
		double midnight = date.toEpochDay() + UNIX_EPOCH_JD;
		// Minutes from 00:00 UTC of the date; start at local solar noon and refine once at the event itself.
		double minutes = 720 - 4 * longitude;
		for (int pass = 0; pass < 2; pass++) {
			double century = (midnight + minutes / 1440 - 2451545) / 36525;
			double[] sun = sun(century);
			double cosine = cos(toRadians(90.833)) / (cos(toRadians(latitude)) * cos(sun[0]))
					- tan(toRadians(latitude)) * tan(sun[0]);
			if (cosine < -1 || cosine > 1) return null;
			double hourAngle = toDegrees(acos(cosine));
			minutes = 720 - 4 * longitude - sun[1] + (rise ? -4 : 4) * hourAngle;
		}
		var time = date.atStartOfDay(ZoneOffset.UTC).plusSeconds(round(minutes * 60)).withZoneSameInstant(KOREA);
		return time.toLocalTime().plusSeconds(30).truncatedTo(ChronoUnit.MINUTES).format(HOURS);
	}
	/** {declination in radians, equation of time in minutes} for Julian century T. */
	private static double[] sun(double century) {
		double c = century;
		double meanLongitude = toRadians((280.46646 + c * (36000.76983 + c * 0.0003032)) % 360);
		double anomaly = toRadians(357.52911 + c * (35999.05029 - 0.0001537 * c));
		double eccentricity = 0.016708634 - c * (0.000042037 + 0.0000001267 * c);
		double center = sin(anomaly) * (1.914602 - c * (0.004817 + 0.000014 * c))
				+ sin(2 * anomaly) * (0.019993 - 0.000101 * c) + sin(3 * anomaly) * 0.000289;
		double node = toRadians(125.04 - 1934.136 * c);
		double apparent = toRadians(toDegrees(meanLongitude) + center - 0.00569 - 0.00478 * sin(node));
		double meanObliquity = 23 + (26 + (21.448 - c * (46.815 + c * (0.00059 - c * 0.001813))) / 60) / 60;
		double obliquity = toRadians(meanObliquity + 0.00256 * cos(node));
		double declination = asin(sin(obliquity) * sin(apparent));
		double y = pow(tan(obliquity / 2), 2);
		double equation = 4 * toDegrees(y * sin(2 * meanLongitude) - 2 * eccentricity * sin(anomaly)
				+ 4 * eccentricity * y * sin(anomaly) * cos(2 * meanLongitude)
				- 0.5 * y * y * sin(4 * meanLongitude) - 1.25 * eccentricity * eccentricity * sin(2 * anomaly));
		return new double[] {declination, equation};
	}
}
