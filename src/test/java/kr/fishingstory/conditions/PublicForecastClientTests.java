package kr.fishingstory.conditions;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PublicForecastClientTests {
	private static final LocalDate DAY = LocalDate.of(2026, 10, 24);
	private final JsonMapper json = JsonMapper.builder().build();
	@Test void unconfiguredAndFreshwaterNeverCallUpstream() {
		var calls = new AtomicInteger();
		var client = client("", "", uri -> { calls.incrementAndGet(); throw new AssertionError(); });
		var result = client.get(FishingLocations.find("incheon"), DAY);
		assertThat(result.weather().status()).isEqualTo("NOT_CONFIGURED");
		assertThat(result.tides().items()).isEmpty();
		var inland = client.get(FishingLocations.find("seoul"), DAY);
		assertThat(inland.sea().status()).isEqualTo("NOT_APPLICABLE");
		assertThat(inland.tides().status()).isEqualTo("NOT_APPLICABLE");
		assertThat(inland.tideLevels().status()).isEqualTo("NOT_APPLICABLE");
		assertThat(result.tideLevels().status()).isEqualTo("NOT_CONFIGURED");
		assertThat(result.weatherSummary()).isNull();
		// The almanac is computed locally, so it is present even without any provider key.
		assertThat(result.almanac().multtae()).isEqualTo("5물");
		assertThat(inland.almanac().multtae()).isNull();
		assertThat(calls).hasValue(0);
	}
	@Test void parsesTideCurveAndHourlyHumidityRainAmountAndDailyExtremes() {
		var client = client("key", "key", uri -> switch (kind(uri)) {
			case "weather" -> response(List.of(weatherRow("20261024", "0600", "TMP", "11"), weatherRow("20261024", "0600", "TMN", "10"),
					weatherRow("20261024", "0600", "REH", "85"), weatherRow("20261024", "0600", "PCP", "1mm 미만"),
					weatherRow("20261024", "1500", "TMP", "19"), weatherRow("20261024", "1500", "TMX", "20"),
					weatherRow("20261024", "1500", "PCP", "강수없음"), weatherRow("20261025", "1500", "TMP", "18")));
			case "level" -> {
				assertThat(uri.getRawQuery()).contains("type=json", "obsCode=DT_0001", "min=30", "numOfRows=300");
				// The last call below asks for the next day; only the first day carries the edge cases.
				yield param(uri, "reqDate").equals("20261024")
						? response(List.of(level("2026-10-24 00:30:00", 120.5), level("2026-10-24 00:00:00", 98),
								level("2026-10-24 01:00:00", -14), level("2026-10-25 00:00:00", 300)))
						: answer(uri);
			}
			default -> answer(uri);
		});
		var result = client.get(FishingLocations.find("incheon"), DAY);
		assertThat(result.tideLevels().status()).isEqualTo("READY");
		assertThat(result.tideLevels().source()).isEqualTo("국립해양조사원 조석예보(시계열)");
		// Sorted by time; the closing next-day midnight is not part of the requested date.
		assertThat(result.tideLevels().items()).extracting(FishingConditions.TideLevel::time).containsExactly("00:00", "00:30", "01:00");
		assertThat(result.tideLevels().items().get(2).heightCm()).isEqualTo(-14);
		assertThat(result.weatherSummary()).isEqualTo(new FishingConditions.WeatherSummary(10.0, 20.0));
		assertThat(result.weather().items()).hasSize(2).first().satisfies(hour -> {
			assertThat(hour.humidityPercent()).isEqualTo(85);
			assertThat(hour.precipitation()).isEqualTo("1mm 미만");
		});
		assertThat(result.weather().items().get(1).precipitation()).isNull();
		assertThat(client.get(FishingLocations.find("incheon"), DAY.plusDays(1)).weatherSummary()).isNull();
	}
	@Test void aTideCurveForAnotherDateFailsOnlyThatSection() {
		var client = client("key", "key", uri -> kind(uri).equals("level")
				? response(List.of(level("2026-10-23 12:00:00", 100))) : answer(uri));
		var result = client.get(FishingLocations.find("incheon"), DAY);
		assertThat(result.tideLevels().status()).isEqualTo("UNAVAILABLE");
		assertThat(List.of(result.weather().status(), result.sea().status(), result.tides().status())).containsOnly("READY");
	}
	@Test void parsesOfficialShapesKeepsMissingNumbersAndZeroAndNegativeTides() {
		var client = client("test+key=", "tide/key", uri -> {
			assertThat(uri.getRawQuery()).contains("serviceKey=");
			if (uri.getPath().endsWith("getVilageFcst")) {
				assertThat(uri.getRawQuery()).contains("test%2Bkey%3D", "base_date=20261024", "base_time=0800", "pageNo=1");
				return response(List.of(weather("TMP", "20"), weather("POP", "0"), weather("SKY", "1"), weather("WSD", "-999")));
			}
			if (uri.getPath().endsWith("getSeaFcst")) return response(List.of(sea("202610240500", 0, "0", "0.5"), sea("202610240500", 2, "3", "4")));
			assertThat(uri.getRawQuery()).contains("type=json", "obsCode=DT_0001", "reqDate=20261024", "tide%2Fkey");
			// KHOA JSON can omit the outer 'response' wrapper and return a single item object.
			return json.writeValueAsString(Map.of("header", Map.of("resultCode", "0"), "body", Map.of("items", Map.of("item",
					Map.of("predcDt", "2026-10-24 10:30:00", "extrSe", 2, "predcTdlvVl", -12.5)))));
		});
		var result = client.get(FishingLocations.find("incheon"), DAY);
		assertThat(result.weather().items()).singleElement().satisfies(hour -> {
			assertThat(hour.precipitationProbability()).isZero(); assertThat(hour.windSpeedMs()).isNull();
			assertThat(hour.time()).isEqualTo("12:00");
		});
		assertThat(result.sea().items()).singleElement().satisfies(period -> {
			assertThat(period.period()).isEqualTo("오전"); assertThat(period.waveHeightMinM()).isZero();
		});
		assertThat(result.tides().items()).singleElement().satisfies(tide -> {
			assertThat(tide.type()).isEqualTo("LOW"); assertThat(tide.heightCm()).isEqualTo(-12.5);
		});
	}
	@Test void afternoonCycleMapsNextMorningAndRejectsWrongTideDateWithoutLosingWeather() {
		var clock = new MutableClock(Instant.parse("2026-10-24T09:00:00Z"));
		var client = new PublicForecastClient("key", "key", uri -> {
			if (uri.getPath().endsWith("getSeaFcst")) return response(List.of(sea("202610241700", 0, "1", "2"), sea("202610241700", 1, "2", "3")));
			if (uri.getPath().contains("tideFcst")) return response(List.of(Map.of("predcDt", "2026-10-23 10:00:00", "extrSe", 1, "predcTdlvVl", 10)));
			return response(List.of(weather("TMP", "20")));
		}, clock);
		var next = client.get(FishingLocations.find("incheon"), DAY.plusDays(1));
		assertThat(next.sea().items()).singleElement().satisfies(period -> {
			assertThat(period.period()).isEqualTo("오전"); assertThat(period.waveHeightMinM()).isEqualTo(2);
		});
		assertThat(next.tides().status()).isEqualTo("UNAVAILABLE");
		var current = client.get(FishingLocations.find("incheon"), DAY);
		assertThat(current.sea().items()).singleElement().satisfies(period -> assertThat(period.period()).isEqualTo("오후"));
		assertThat(current.weather().status()).isEqualTo("READY");
	}
	@Test void reusesOnePublicationAcrossDatesAndReturnsStaleOnlyWithinThreeHours() {
		var clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
		var calls = new AtomicInteger();
		var failing = new AtomicBoolean();
		var client = new PublicForecastClient("key", "", uri -> {
			calls.incrementAndGet();
			if (failing.get()) throw new java.io.IOException("unavailable");
			return response(List.of(weatherAt("20261024", "TMP", "20"), weatherAt("20261025", "TMP", "18")));
		}, clock);
		var first = client.get(FishingLocations.find("seoul"), DAY);
		assertThat(client.get(FishingLocations.find("seoul"), DAY.plusDays(1)).weather().status()).isEqualTo("READY");
		assertThat(client.get(FishingLocations.find("seoul"), DAY.plusDays(2)).weather().status()).isEqualTo("EMPTY");
		clock.now = clock.now.plus(Duration.ofMinutes(20));
		client.get(FishingLocations.find("seoul"), DAY);
		// One publication serves every offered date until a newer one is due.
		assertThat(calls).hasValue(1);

		clock.now = Instant.parse("2026-10-24T02:20:00Z");
		failing.set(true);
		var stale = client.get(FishingLocations.find("seoul"), DAY);
		assertThat(calls).hasValue(2);
		assertThat(stale.weather().status()).isEqualTo("STALE");
		assertThat(stale.weather().fetchedAt()).isEqualTo(first.weather().fetchedAt());
		assertThat(stale.weather().issuedAt()).isEqualTo("2026-10-24T08:00");
		assertThat(client.get(FishingLocations.find("seoul"), DAY.plusDays(2)).weather().status()).isEqualTo("UNAVAILABLE");
		assertThat(calls).hasValue(2);
		clock.now = Instant.parse("2026-10-24T03:01:00Z");
		assertThat(client.get(FishingLocations.find("seoul"), DAY).weather().status()).isEqualTo("UNAVAILABLE");
	}
	@Test void cachesEachSourceOnceForAllDatesAndKeepsTidesForHalfADay() {
		var clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
		var calls = new ConcurrentHashMap<String, AtomicInteger>();
		var client = new PublicForecastClient("key", "key", uri -> {
			calls.computeIfAbsent(kind(uri), ignored -> new AtomicInteger()).incrementAndGet();
			return answer(uri);
		}, clock);
		for (int offset = 0; offset < 3; offset++) client.get(FishingLocations.find("incheon"), DAY.plusDays(offset));
		assertThat(count(calls, "weather")).isEqualTo(1);
		assertThat(count(calls, "sea")).isEqualTo(1);
		assertThat(count(calls, "tide")).isEqualTo(3);
		assertThat(count(calls, "level")).isEqualTo(3);
		clock.now = clock.now.plus(Duration.ofMinutes(20));
		for (int offset = 0; offset < 3; offset++) client.get(FishingLocations.find("incheon"), DAY.plusDays(offset));
		assertThat(count(calls, "weather") + count(calls, "sea") + count(calls, "tide") + count(calls, "level")).isEqualTo(8);
		clock.now = Instant.parse("2026-10-24T06:00:00Z");
		var later = client.get(FishingLocations.find("incheon"), DAY);
		// A newer short-range publication and the 30-minute marine refresh are due; tide predictions are not.
		assertThat(count(calls, "weather")).isEqualTo(2);
		assertThat(count(calls, "sea")).isEqualTo(2);
		assertThat(count(calls, "tide")).isEqualTo(3);
		assertThat(count(calls, "level")).isEqualTo(3);
		assertThat(later.tides().status()).isEqualTo("READY");
		assertThat(later.tideLevels().status()).isEqualTo("READY");
	}
	@Test void slowSourceNeitherBlocksOtherLocationsNorFetchesTwiceForConcurrentCallers() throws Exception {
		var release = new CountDownLatch(1);
		var started = new CountDownLatch(1);
		var blockedCalls = new AtomicInteger();
		var client = client("key", "key", uri -> {
			if (kind(uri).equals("tide") && uri.getRawQuery().contains("obsCode=DT_0001")) {
				blockedCalls.incrementAndGet();
				started.countDown();
				if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("not released");
			}
			return answer(uri);
		});
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> client.get(FishingLocations.find("incheon"), DAY));
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			var second = pool.submit(() -> client.get(FishingLocations.find("incheon"), DAY));
			var other = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> client.get(FishingLocations.find("busan"), DAY));
			assertThat(other.tides().status()).isEqualTo("READY");
			assertThat(second.isDone()).isFalse();
			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).tides().status()).isEqualTo("READY");
			assertThat(second.get(5, TimeUnit.SECONDS).tides().status()).isEqualTo("READY");
		}
		assertThat(blockedCalls).hasValue(1);
	}
	@Test void fetchesWeatherMarineAndTideSourcesInParallel() {
		var arrived = new CountDownLatch(4);
		var client = client("key", "key", uri -> {
			arrived.countDown();
			// Sequential calls would wait here for sources that have not started yet.
			if (!arrived.await(2, TimeUnit.SECONDS)) throw new java.io.IOException("sources were fetched one by one");
			return answer(uri);
		});
		var result = client.get(FishingLocations.find("incheon"), DAY);
		assertThat(List.of(result.weather().status(), result.sea().status(), result.tides().status(),
				result.tideLevels().status())).containsOnly("READY");
	}
	@Test void backsOffRepeatedFailuresOfOneSource() {
		var clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
		var calls = new AtomicInteger();
		var client = new PublicForecastClient("key", "", uri -> { calls.incrementAndGet(); throw new java.io.IOException("quota"); }, clock);
		var seoul = FishingLocations.find("seoul");
		assertThat(client.get(seoul, DAY).weather().status()).isEqualTo("UNAVAILABLE");
		client.get(seoul, DAY);
		assertThat(calls).hasValue(1);
		clock.now = clock.now.plusSeconds(61);
		client.get(seoul, DAY);
		assertThat(calls).hasValue(2);
		clock.now = clock.now.plusSeconds(60);
		client.get(seoul, DAY);
		assertThat(calls).hasValue(2);
		clock.now = clock.now.plusSeconds(61);
		client.get(seoul, DAY);
		assertThat(calls).hasValue(3);
		clock.now = clock.now.plusSeconds(299);
		client.get(seoul, DAY);
		assertThat(calls).hasValue(3);
		clock.now = clock.now.plusSeconds(2);
		client.get(seoul, DAY);
		assertThat(calls).hasValue(4);
	}
	@Test void openCircuitStopsCallingAFailingProviderForOtherLocations() {
		var clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
		var weatherCalls = new AtomicInteger();
		var client = new PublicForecastClient("key", "", uri -> {
			if (!kind(uri).equals("weather")) return answer(uri);
			weatherCalls.incrementAndGet();
			throw new java.io.IOException("quota exceeded");
		}, clock);
		for (var id : List.of("incheon", "mokpo", "busan")) client.get(FishingLocations.find(id), DAY);
		assertThat(weatherCalls).hasValue(3);
		var sokcho = client.get(FishingLocations.find("sokcho"), DAY);
		assertThat(weatherCalls).hasValue(3);
		assertThat(sokcho.weather().status()).isEqualTo("UNAVAILABLE");
		// Only the failing API is paused; the marine forecast is a separate provider API.
		assertThat(sokcho.sea().status()).isEqualTo("READY");
		clock.now = clock.now.plusSeconds(61);
		client.get(FishingLocations.find("sokcho"), DAY);
		assertThat(weatherCalls).hasValue(4);
	}
	@Test void pagesShortRangeForecastOnlyUntilTheNeededDays() {
		var pages = Collections.synchronizedList(new ArrayList<String>());
		var client = client("key", "", uri -> {
			var page = param(uri, "pageNo");
			pages.add(page);
			return page.equals("1")
					? weatherPage(1500, List.of(weatherAt("20261024", "TMP", "20"), weatherAt("20261025", "TMP", "19")))
					: weatherPage(1500, List.of(weatherAt("20261026", "TMP", "18"), weatherAt("20261028", "TMP", "17")));
		});
		var result = client.get(FishingLocations.find("seoul"), DAY.plusDays(2));
		assertThat(result.weather().status()).isEqualTo("READY");
		assertThat(pages).containsExactly("1", "2");

		var single = Collections.synchronizedList(new ArrayList<String>());
		var complete = client("key", "", uri -> {
			single.add(param(uri, "pageNo"));
			return weatherPage(1500, List.of(weatherAt("20261026", "TMP", "18"), weatherAt("20261028", "TMP", "17")));
		});
		assertThat(complete.get(FishingLocations.find("seoul"), DAY.plusDays(2)).weather().status()).isEqualTo("READY");
		assertThat(single).containsExactly("1");
	}
	@Test void showersAreNotReportedAsRain() {
		var client = client("key", "", uri -> response(List.of(weather("PTY", "4"), weather("SKY", "1"), weather("TMP", "17"))));
		assertThat(client.get(FishingLocations.find("seoul"), DAY).weather().items())
				.singleElement().satisfies(hour -> assertThat(hour.sky()).isEqualTo("소나기"));
	}
	@Test void koreaDateAndEarlyMorningUsePreviousDayPublication() {
		var clock = new MutableClock(Instant.parse("2026-10-23T15:05:00Z"));
		var client = new PublicForecastClient("key", "", uri -> {
			assertThat(uri.getRawQuery()).contains("base_date=20261023", "base_time=2300");
			return response(List.of());
		}, clock);
		assertThat(client.today()).isEqualTo(DAY);
		assertThat(client.get(FishingLocations.find("seoul"), DAY).weather().status()).isEqualTo("EMPTY");
	}
	@Test void distinguishesProviderErrorFromNoDataAndNeverExposesKeysOrBody() {
		var client = client("secret-key", "", uri -> "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"secret-key\"}}}");
		var result = client.get(FishingLocations.find("seoul"), DAY);
		assertThat(result.weather().status()).isEqualTo("UNAVAILABLE");
		assertThat(result.toString()).doesNotContain("secret-key", "resultMsg");
		var empty = client("key", "", uri -> "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}");
		assertThat(empty.get(FishingLocations.find("seoul"), DAY).weather().status()).isEqualTo("EMPTY");
	}
	private PublicForecastClient client(String weather, String tide, PublicForecastClient.Transport transport) {
		return new PublicForecastClient(weather, tide, transport, Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));
	}
	/** Valid answers for every source, with tide rows on the requested date. */
	private String answer(URI uri) {
		return switch (kind(uri)) {
			case "weather" -> response(List.of(weatherAt("20261024", "TMP", "20"), weatherAt("20261025", "TMP", "19"),
					weatherAt("20261026", "TMP", "18")));
			case "sea" -> {
				var region = param(uri, "regId");
				yield response(List.of(seaFor(region, "202610240500", 0, "0.5", "1"), seaFor(region, "202610240500", 2, "1", "1.5"),
						seaFor(region, "202610240500", 4, "1", "2")));
			}
			case "level" -> {
				var day = param(uri, "reqDate");
				var date = day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6);
				yield response(List.of(level(date + " 10:00:00", 600), level(date + " 10:30:00", 610)));
			}
			default -> {
				var day = param(uri, "reqDate");
				yield response(List.of(Map.of("predcDt", day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6) + " 10:30:00",
						"extrSe", 1, "predcTdlvVl", 610)));
			}
		};
	}
	private static String kind(URI uri) {
		if (uri.getPath().endsWith("getVilageFcst")) return "weather";
		if (uri.getPath().endsWith("GetTideFcstTimeApiService")) return "level";
		return uri.getPath().endsWith("getSeaFcst") ? "sea" : "tide";
	}
	private static String param(URI uri, String name) {
		for (var pair : uri.getRawQuery().split("&")) {
			var parts = pair.split("=", 2);
			if (parts[0].equals(name)) return parts[1];
		}
		throw new AssertionError("missing " + name);
	}
	private static int count(Map<String, AtomicInteger> calls, String kind) {
		var value = calls.get(kind);
		return value == null ? 0 : value.get();
	}
	private String response(Object rows) {
		return json.writeValueAsString(Map.of("response", Map.of("header", Map.of("resultCode", "00"), "body", Map.of("items", Map.of("item", rows)))));
	}
	private String weatherPage(int total, Object rows) {
		return json.writeValueAsString(Map.of("response", Map.of("header", Map.of("resultCode", "00"),
				"body", Map.of("totalCount", total, "items", Map.of("item", rows)))));
	}
	private Map<String, Object> weather(String category, String value) {
		return weatherAt("20261024", category, value);
	}
	private Map<String, Object> weatherAt(String date, String category, String value) {
		return weatherRow(date, "1200", category, value);
	}
	private Map<String, Object> weatherRow(String date, String time, String category, String value) {
		return Map.of("fcstDate", date, "fcstTime", time, "category", category, "fcstValue", value);
	}
	private Map<String, Object> level(String time, double height) {
		return Map.of("obsvtrNm", "인천", "lat", 37.45, "lot", 126.59, "predcDt", time, "tdlvHgt", height);
	}
	private Map<String, Object> sea(String issued, int offset, String low, String high) {
		return seaFor("12A20100", issued, offset, low, high);
	}
	private Map<String, Object> seaFor(String region, String issued, int offset, String low, String high) {
		return Map.of("tmFc", issued, "numEf", offset, "regId", region, "wd1", "N", "wd2", "NE", "ws1", "3", "ws2", "6", "wh1", low, "wh2", high, "wf", "맑음");
	}
	private static class MutableClock extends Clock {
		volatile Instant now;
		MutableClock(Instant now) { this.now = now; }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
		@Override public Instant instant() { return now; }
	}
}
