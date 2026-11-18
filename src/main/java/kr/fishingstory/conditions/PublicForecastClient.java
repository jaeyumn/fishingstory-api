package kr.fishingstory.conditions;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static kr.fishingstory.conditions.FishingConditions.*;

@Component
public class PublicForecastClient implements AutoCloseable {
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
	private static final String WEATHER_URL = "https://www.data.go.kr/data/15084084/openapi.do";
	private static final String SEA_URL = "https://www.weather.go.kr/w/forecast/ocean.do";
	private static final String TIDE_URL = "https://www.data.go.kr/data/15156018/openapi.do";
	private static final String TIDE_LEVEL_URL = "https://www.data.go.kr/data/15156022/openapi.do";
	private static final String WEATHER_SOURCE = "기상청 단기예보";
	private static final String SEA_SOURCE = "기상청 해상예보";
	private static final String TIDE_SOURCE = "국립해양조사원 조석예보";
	private static final String TIDE_LEVEL_SOURCE = "국립해양조사원 조석예보(시계열)";
	// One provider response serves every offered date. Short-range data is reused until a newer
	// publication is due; tide predictions are astronomical and do not change during the day.
	private static final Duration WEATHER_TTL = Duration.ofHours(3);
	private static final Duration SEA_TTL = Duration.ofMinutes(30);
	private static final Duration TIDE_TTL = Duration.ofHours(12);
	private static final Duration FORECAST_STALE = Duration.ofHours(3);
	private static final Duration TIDE_STALE = Duration.ofHours(24);
	private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
			Duration.ofMinutes(5), Duration.ofMinutes(15));
	private static final int WEATHER_PAGE_ROWS = 1000;
	private static final int WEATHER_MAX_PAGES = 3;
	/** Minutes between tide-curve points: 48 points a day in one page (the provider allows up to 300 rows). */
	private static final int TIDE_LEVEL_INTERVAL = 30;
	private final String weatherKey;
	private final String tideKey;
	private final Transport transport;
	private final Clock clock;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
	private final Map<String, Breaker> breakers = Map.of("weather", new Breaker(), "sea", new Breaker(), "tide", new Breaker(),
			"tideLevel", new Breaker());
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@Autowired
	public PublicForecastClient(@Value("${FISHING_KMA_SERVICE_KEY:}") String weatherKey,
			@Value("${FISHING_TIDE_SERVICE_KEY:}") String tideKey) {
		this(weatherKey, tideKey, httpTransport(), Clock.systemUTC());
	}
	PublicForecastClient(String weatherKey, String tideKey, Transport transport, Clock clock) {
		this.weatherKey = weatherKey.strip(); this.tideKey = tideKey.strip(); this.transport = transport; this.clock = clock;
	}
	public LocalDate today() { return LocalDate.now(clock.withZone(KOREA)); }
	public FishingConditions get(FishingLocations.Location location, LocalDate date) {
		// No database transaction or member lock is held during these bounded external calls.
		forgetOldSlots();
		var weather = CompletableFuture.supplyAsync(() -> weather(location, date), executor);
		var sea = CompletableFuture.supplyAsync(() -> sea(location, date), executor);
		var tides = CompletableFuture.supplyAsync(() -> tides(location, date), executor);
		var levels = CompletableFuture.supplyAsync(() -> tideLevels(location, date), executor);
		var almanac = AlmanacCalculator.of(location, date);
		var hours = weather.join();
		return new FishingConditions(location, date, clock.instant(), hours.section(), hours.summary(), sea.join(),
				tides.join(), levels.join(), almanac);
	}
	/** Spring calls this on shutdown because the bean is AutoCloseable. */
	@Override public void close() { executor.close(); }

	private WeatherPart weather(FishingLocations.Location place, LocalDate date) {
		if (weatherKey.isEmpty()) return new WeatherPart(empty("NOT_CONFIGURED", WEATHER_SOURCE, WEATHER_URL, place.weatherBasis()), null);
		var base = weatherBase();
		Snapshot<WeatherData> snapshot = load("weather/" + place.nx() + "," + place.ny(), "weather", base.toString(), null,
				WEATHER_TTL, () -> fetchWeather(place, base));
		var section = section(snapshot, FORECAST_STALE, WEATHER_SOURCE, WEATHER_URL, place.weatherBasis(),
				WeatherData::base, data -> data.days().getOrDefault(date, List.of()));
		// Daily extremes are shown only alongside the hours of the same publication.
		return new WeatherPart(section, section.items().isEmpty() ? null : snapshot.value().summaries().get(date));
	}
	private Section<SeaPeriod> sea(FishingLocations.Location place, LocalDate date) {
		if (place.seaCode() == null) return empty("NOT_APPLICABLE", SEA_SOURCE, SEA_URL, "해상 정보 대상 아님");
		if (weatherKey.isEmpty()) return empty("NOT_CONFIGURED", SEA_SOURCE, SEA_URL, place.seaBasis());
		Snapshot<SeaData> snapshot = load("sea/" + place.seaCode(), "sea", null, null, SEA_TTL, () -> fetchSea(place));
		return section(snapshot, FORECAST_STALE, SEA_SOURCE, SEA_URL, place.seaBasis(),
				data -> data.issued().get(date), data -> data.days().getOrDefault(date, List.of()));
	}
	private Section<TideEvent> tides(FishingLocations.Location place, LocalDate date) {
		if (place.tideCode() == null) return empty("NOT_APPLICABLE", TIDE_SOURCE, TIDE_URL, "조석 정보 대상 아님");
		if (tideKey.isEmpty()) return empty("NOT_CONFIGURED", TIDE_SOURCE, TIDE_URL, place.tideBasis());
		Snapshot<List<TideEvent>> snapshot = load("tides/" + place.tideCode() + "/" + date, "tide", null, date,
				TIDE_TTL, () -> fetchTides(place, date));
		return section(snapshot, TIDE_STALE, TIDE_SOURCE, TIDE_URL, place.tideBasis(), data -> null, data -> data);
	}
	private Section<TideLevel> tideLevels(FishingLocations.Location place, LocalDate date) {
		if (place.tideCode() == null) return empty("NOT_APPLICABLE", TIDE_LEVEL_SOURCE, TIDE_LEVEL_URL, "조석 정보 대상 아님");
		if (tideKey.isEmpty()) return empty("NOT_CONFIGURED", TIDE_LEVEL_SOURCE, TIDE_LEVEL_URL, place.tideBasis());
		Snapshot<List<TideLevel>> snapshot = load("tideLevels/" + place.tideCode() + "/" + date, "tideLevel", null, date,
				TIDE_TTL, () -> fetchTideLevels(place, date));
		return section(snapshot, TIDE_STALE, TIDE_LEVEL_SOURCE, TIDE_LEVEL_URL, place.tideBasis(), data -> null, data -> data);
	}

	/** Single flight per key: one caller fetches outside every lock while the others wait for it. */
	@SuppressWarnings("unchecked")
	private <T> Snapshot<T> load(String key, String api, String tag, LocalDate forDate, Duration ttl, Loader<T> loader) {
		var slot = slots.computeIfAbsent(key, ignored -> new Slot(forDate));
		var breaker = breakers.get(api);
		CompletableFuture<Void> waiting;
		boolean owner = false;
		synchronized (slot) {
			var now = clock.instant();
			slot.touchedAt = now;
			// A newer publication (tag) is due at once unless the last attempt failed and is backing off.
			boolean due = !now.isBefore(slot.refreshAt) || (!slot.failed && !Objects.equals(tag, slot.tag));
			if (!due) return (Snapshot<T>) slot.snapshot();
			if (slot.loading == null) {
				if (breaker.open(now)) return (Snapshot<T>) slot.unavailable();
				slot.loading = new CompletableFuture<>();
				owner = true;
			}
			waiting = slot.loading;
		}
		if (!owner) {
			waiting.join();
			synchronized (slot) { return (Snapshot<T>) slot.snapshot(); }
		}
		T value = null;
		boolean success = false;
		try {
			value = loader.load();
			success = true;
		} catch (Exception exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
		} finally {
			var now = clock.instant();
			synchronized (slot) {
				if (success) {
					slot.value = value; slot.tag = tag; slot.fetchedAt = now;
					slot.failed = false; slot.failures = 0; slot.refreshAt = now.plus(ttl);
				} else {
					slot.failed = true;
					slot.refreshAt = now.plus(BACKOFF.get(Math.min(slot.failures, BACKOFF.size() - 1)));
					slot.failures++;
				}
				slot.loading = null;
			}
			if (success) breaker.success(); else breaker.failure(now);
			waiting.complete(null);
		}
		synchronized (slot) { return (Snapshot<T>) slot.snapshot(); }
	}
	private <D, T> Section<T> section(Snapshot<D> snapshot, Duration staleFor, String source, String url, String basis,
			Function<D, String> issued, Function<D, List<T>> items) {
		var now = clock.instant();
		var data = snapshot.value();
		if (data == null || (snapshot.failed() && !snapshot.fetchedAt().plus(staleFor).isAfter(now))) {
			return empty("UNAVAILABLE", source, url, basis);
		}
		var values = items.apply(data);
		if (snapshot.failed()) {
			// A failed refresh shows earlier data only when that data covers the requested date.
			return values.isEmpty() ? empty("UNAVAILABLE", source, url, basis)
					: new Section<>("STALE", source, url, basis, snapshot.fetchedAt(), issued.apply(data), values);
		}
		return new Section<>(values.isEmpty() ? "EMPTY" : "READY", source, url, basis, snapshot.fetchedAt(), issued.apply(data), values);
	}
	private <T> Section<T> empty(String status, String source, String url, String basis) {
		return new Section<>(status, source, url, basis, clock.instant(), null, List.of());
	}
	private void forgetOldSlots() {
		var now = clock.instant();
		var today = today();
		slots.entrySet().removeIf(entry -> {
			var slot = entry.getValue();
			synchronized (slot) {
				return slot.loading == null && ((slot.forDate != null && slot.forDate.isBefore(today))
						|| slot.touchedAt.plus(TIDE_STALE).isBefore(now));
			}
		});
	}

	private LocalDateTime weatherBase() {
		// Publication has a delivery delay. Use the last cycle at least 15 minutes old.
		var now = LocalDateTime.now(clock.withZone(KOREA)).minusMinutes(15);
		var base = now.withHour(2).withMinute(0).withSecond(0).withNano(0);
		if (base.isAfter(now)) return base.minusDays(1).withHour(23);
		return base.plusHours(((now.getHour() - 2) / 3) * 3L);
	}
	private WeatherData fetchWeather(FishingLocations.Location place, LocalDateTime base) throws Exception {
		// One publication is reused across midnight, so cover the day that becomes 모레 as well.
		var lastNeeded = today().plusDays(3);
		var days = new TreeMap<LocalDate, TreeMap<String, Map<String, Object>>>();
		for (int page = 1; page <= WEATHER_MAX_PAGES; page++) {
			var root = request("1360000/VilageFcstInfoService_2.0/getVilageFcst", weatherKey, Map.of(
					"dataType", "JSON", "numOfRows", "" + WEATHER_PAGE_ROWS, "pageNo", "" + page,
					"base_date", base.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE),
					"base_time", base.toLocalTime().format(DateTimeFormatter.ofPattern("HHmm")), "nx", "" + place.nx(), "ny", "" + place.ny()));
			var rows = items(root);
			boolean beyondNeeded = false;
			for (var row : rows) {
				var day = LocalDate.parse(text(row, "fcstDate"), DateTimeFormatter.BASIC_ISO_DATE);
				var time = text(row, "fcstTime");
				if (!time.matches("(?:[01][0-9]|2[0-3])[0-5][0-9]")) throw new IllegalArgumentException();
				beyondNeeded |= day.isAfter(lastNeeded);
				days.computeIfAbsent(day, ignored -> new TreeMap<>()).computeIfAbsent(time, ignored -> new HashMap<>())
						.put(text(row, "category"), row.get("fcstValue"));
			}
			// Rows arrive in forecast order; since the 2024 range extension one page may stop before the needed days.
			long total = totalCount(root);
			if (rows.isEmpty() || beyondNeeded || total < 0 || (long) page * WEATHER_PAGE_ROWS >= total) break;
		}
		var result = new TreeMap<LocalDate, List<WeatherHour>>();
		var summaries = new HashMap<LocalDate, WeatherSummary>();
		days.forEach((day, hours) -> {
			var list = new ArrayList<WeatherHour>();
			Double lowest = null, highest = null;
			for (var hour : hours.entrySet()) {
				var values = hour.getValue();
				// KMA puts the daily lowest on the 06:00 row and the highest on the 15:00 row.
				if (lowest == null) lowest = number(values, "TMN", -80, 60);
				if (highest == null) highest = number(values, "TMX", -80, 60);
				var temp = number(values, "TMP", -80, 60);
				var rain = number(values, "POP", 0, 100);
				var speed = number(values, "WSD", 0, 150);
				var direction = number(values, "VEC", 0, 360);
				if (temp == null && rain == null && speed == null && !values.containsKey("SKY")) continue;
				list.add(new WeatherHour(hour.getKey().substring(0, 2) + ":" + hour.getKey().substring(2),
						temp, rain, speed, direction, sky(values), number(values, "REH", 0, 100), precipitation(values)));
			}
			if (!list.isEmpty()) result.put(day, List.copyOf(list));
			if (lowest != null || highest != null) summaries.put(day, new WeatherSummary(lowest, highest));
		});
		return new WeatherData(base.toString(), result, Map.copyOf(summaries));
	}
	private SeaData fetchSea(FishingLocations.Location place) throws Exception {
		var root = request("1360000/VilageFcstMsgService/getSeaFcst", weatherKey,
				Map.of("dataType", "JSON", "pageNo", "1", "numOfRows", "100", "regId", place.seaCode()));
		var days = new TreeMap<LocalDate, TreeMap<Integer, SeaPeriod>>();
		var issued = new HashMap<LocalDate, String>();
		for (var row : items(root)) {
			if (!text(row, "regId").equals(place.seaCode())) throw new IllegalArgumentException();
			var announcement = parseTime(text(row, "tmFc"));
			if (announcement.isAfter(LocalDateTime.now(clock.withZone(KOREA)).plusMinutes(5))
					|| announcement.isBefore(LocalDateTime.now(clock.withZone(KOREA)).minusHours(24))) throw new IllegalArgumentException();
			// KMA guide: 05:00 cycle starts with today's AM; 11:00/17:00 with today's PM.
			int offset = integer(row, "numEf", 0, 10) + (announcement.getHour() < 11 ? 0 : 1);
			var day = announcement.toLocalDate().plusDays(offset / 2);
			issued.put(day, announcement.toString());
			var lowWind = number(row, "ws1", 0, 150); var highWind = number(row, "ws2", 0, 150);
			var lowWave = number(row, "wh1", 0, 50); var highWave = number(row, "wh2", 0, 50);
			if ((lowWind != null && highWind != null && lowWind > highWind)
					|| (lowWave != null && highWave != null && lowWave > highWave)) throw new IllegalArgumentException();
			days.computeIfAbsent(day, ignored -> new TreeMap<>()).put(offset, new SeaPeriod(offset % 2 == 0 ? "오전" : "오후",
					text(row, "wd1").isBlank() ? "정보 없음" : text(row, "wd1") + (text(row, "wd2").isBlank() || text(row, "wd1").equals(text(row, "wd2")) ? "" : "~" + text(row, "wd2")),
					lowWind, highWind, lowWave, highWave, text(row, "wf").isBlank() ? "정보 없음" : text(row, "wf")));
		}
		var result = new TreeMap<LocalDate, List<SeaPeriod>>();
		days.forEach((day, periods) -> result.put(day, List.copyOf(periods.values())));
		return new SeaData(result, Map.copyOf(issued));
	}
	private List<TideEvent> fetchTides(FishingLocations.Location place, LocalDate date) throws Exception {
		var root = request("1192136/tideFcstHghLw/GetTideFcstHghLwApiService", tideKey,
				Map.of("type", "json", "pageNo", "1", "numOfRows", "300", "obsCode", place.tideCode(), "reqDate", date.format(DateTimeFormatter.BASIC_ISO_DATE)));
		var result = new TreeMap<String, TideEvent>();
		for (var row : items(root)) {
			var time = parseTime(text(row, "predcDt"));
			if (!time.toLocalDate().equals(date)) throw new IllegalArgumentException();
			int type = integer(row, "extrSe", 1, 4);
			var event = new TideEvent(time.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
					type == 1 || type == 3 ? "HIGH" : "LOW", requiredNumber(row, "predcTdlvVl", -1000, 3000));
			if (result.put(event.time(), event) != null) throw new IllegalArgumentException();
		}
		return List.copyOf(result.values());
	}
	private List<TideLevel> fetchTideLevels(FishingLocations.Location place, LocalDate date) throws Exception {
		var root = request("1192136/tideFcstTime/GetTideFcstTimeApiService", tideKey,
				Map.of("type", "json", "pageNo", "1", "numOfRows", "300", "obsCode", place.tideCode(),
						"reqDate", date.format(DateTimeFormatter.BASIC_ISO_DATE), "min", "" + TIDE_LEVEL_INTERVAL));
		var result = new TreeMap<String, TideLevel>();
		for (var row : items(root)) {
			var time = parseTime(text(row, "predcDt"));
			if (!time.toLocalDate().equals(date)) {
				// A series may close with the next midnight; any other date answers a different request.
				if (time.equals(date.plusDays(1).atStartOfDay())) continue;
				throw new IllegalArgumentException();
			}
			var level = new TideLevel(time.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
					requiredNumber(row, "tdlvHgt", -1000, 3000));
			if (result.put(level.time(), level) != null) throw new IllegalArgumentException();
		}
		return List.copyOf(result.values());
	}
	@SuppressWarnings("unchecked")
	private Map<String, Object> request(String path, String key, Map<String, String> params) throws Exception {
		var query = new StringBuilder("serviceKey=").append(encode(key));
		params.forEach((name, value) -> query.append('&').append(name).append('=').append(encode(value)));
		var body = transport.get(URI.create("https://apis.data.go.kr/" + path + "?" + query));
		if (body.length() > 2_000_000) throw new IllegalArgumentException();
		var root = (Map<String, Object>) json.readValue(body, Map.class);
		if (root.get("response") instanceof Map<?, ?> response) root = (Map<String, Object>) response;
		var code = text(map(root.get("header")), "resultCode");
		if (code.equals("03") || code.equals("3")) return Map.of("body", Map.of("items", Map.of("item", List.of())));
		if (!code.equals("00") && !code.equals("0")) throw new IllegalArgumentException();
		if (!(root.get("body") instanceof Map<?, ?>)) throw new IllegalArgumentException();
		return root;
	}
	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> data ? (Map<String, Object>) data : Map.of();
	}
	private static List<Map<String, Object>> items(Map<String, Object> root) {
		var body = map(root.get("body"));
		var value = map(body.get("items")).get("item");
		if (value == null) {
			if ("0".equals(text(body, "totalCount")) || "0".equals(text(map(body.get("items")), "totalCount"))) return List.of();
			throw new IllegalArgumentException();
		}
		if (value instanceof Map<?, ?>) return List.of(map(value));
		if (!(value instanceof List<?> rows) || rows.size() > 1000) throw new IllegalArgumentException();
		var result = new ArrayList<Map<String, Object>>();
		for (var row : rows) { if (!(row instanceof Map<?, ?>)) throw new IllegalArgumentException(); result.add(map(row)); }
		return result;
	}
	/** -1 when the provider omits the total; callers then read a single page. */
	private static long totalCount(Map<String, Object> root) {
		try { return Long.parseLong(text(map(root.get("body")), "totalCount")); }
		catch (NumberFormatException missing) { return -1; }
	}
	private static String text(Map<String, Object> row, String name) { return Objects.toString(row.get(name), ""); }
	private static Double number(Map<String, Object> row, String key, double min, double max) {
		try {
			var value = Double.valueOf(text(row, key));
			return Double.isFinite(value) && value >= min && value <= max ? value : null;
		} catch (NumberFormatException exception) { return null; }
	}
	private static Double requiredNumber(Map<String, Object> row, String key, double min, double max) {
		var value = number(row, key, min, max); if (value == null) throw new IllegalArgumentException(); return value;
	}
	private static int integer(Map<String, Object> row, String key, int min, int max) {
		var value = requiredNumber(row, key, min, max);
		if (value != Math.rint(value)) throw new IllegalArgumentException();
		return value.intValue();
	}
	private static String sky(Map<String, Object> values) {
		var rain = text(values, "PTY");
		if (!rain.isEmpty() && !rain.equals("0")) return switch (rain) { case "1" -> "비"; case "2" -> "비/눈"; case "3" -> "눈"; case "4" -> "소나기"; default -> "강수"; };
		return switch (text(values, "SKY")) { case "1" -> "맑음"; case "3" -> "구름많음"; case "4" -> "흐림"; default -> "정보 없음"; };
	}
	/** KMA sends PCP as a category label ("1mm 미만", "1.0mm", "30.0~50.0mm", "50.0mm 이상"); none is "강수없음". */
	private static String precipitation(Map<String, Object> values) {
		var value = text(values, "PCP").strip();
		if (value.isEmpty() || value.equals("강수없음") || value.equals("0") || value.equals("-")) return null;
		return value.length() <= 20 && value.matches("[0-9.~ a-z가-힣]+") ? value : null;
	}
	private static LocalDateTime parseTime(String value) {
		if (value.matches("[0-9]{12}([0-9]{2})?")) return LocalDateTime.parse(value.substring(0, 12), COMPACT);
		return LocalDateTime.parse(value.replace(' ', 'T'));
	}
	private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
	private static Transport httpTransport() {
		var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
		return uri -> {
			var future = client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
			try {
				var response = future.get(4, TimeUnit.SECONDS);
				if (response.statusCode() != 200) throw new IllegalArgumentException();
				return response.body();
			} finally { if (!future.isDone()) future.cancel(true); }
		};
	}

	private record WeatherData(String base, Map<LocalDate, List<WeatherHour>> days, Map<LocalDate, WeatherSummary> summaries) { }
	private record WeatherPart(Section<WeatherHour> section, WeatherSummary summary) { }
	private record SeaData(Map<LocalDate, List<SeaPeriod>> days, Map<LocalDate, String> issued) { }
	private record Snapshot<T>(T value, Instant fetchedAt, boolean failed) { }
	/** Guarded by its own monitor; never held while calling a provider. */
	private static final class Slot {
		private final LocalDate forDate;
		private Object value;
		private String tag;
		private Instant fetchedAt;
		private Instant refreshAt = Instant.MIN;
		private Instant touchedAt = Instant.MIN;
		private boolean failed;
		private int failures;
		private CompletableFuture<Void> loading;
		private Slot(LocalDate forDate) { this.forDate = forDate; }
		private Snapshot<Object> snapshot() { return new Snapshot<>(value, fetchedAt, failed); }
		/** The provider was not called (circuit open); earlier data may still be shown as stale. */
		private Snapshot<Object> unavailable() { return new Snapshot<>(value, fetchedAt, true); }
	}
	/** Stops calling one provider API for a while after consecutive failures on any of its keys. */
	private static final class Breaker {
		private int consecutive;
		private Instant openUntil = Instant.MIN;
		private synchronized boolean open(Instant now) { return now.isBefore(openUntil); }
		private synchronized void success() { consecutive = 0; openUntil = Instant.MIN; }
		private synchronized void failure(Instant now) {
			if (++consecutive >= 3) openUntil = now.plus(BACKOFF.get(Math.min(consecutive - 3, BACKOFF.size() - 1)));
		}
	}
	@FunctionalInterface private interface Loader<T> { T load() throws Exception; }
	@FunctionalInterface interface Transport { String get(URI uri) throws Exception; }
}
