package kr.fishingstory.catchreport.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.catchreport.command.application.CreateCatchReportCommand;
import kr.fishingstory.catchreport.command.application.CreateCatchReportService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CatchReportApiTests.DatabaseConfiguration.class)
class CatchReportApiTests {
	static final long USER_ID = 9007199254740993L;
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ObjectMapper json;
	@MockitoSpyBean CreateCatchReportService createService;
	String access;

	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_fish_collection");
		jdbc.update("DELETE FROM fishingstory.catch_report_species");
		jdbc.update("DELETE FROM fishingstory.catch_reports");
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		insertMember(USER_ID, "Fisher", "fisher@example.com");
		insertMember(456, "Other", "other@example.com");
		access = tokens.issue(USER_ID, "USER", UUID.randomUUID().toString()).value();
	}

	@Test void createsPrivateNoCatchReportAndListsOnlyOwnData() throws Exception {
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"catchStatus", "NO_CATCH", "waterType", "SEA",
						"fishingDate", "2026-09-20", "fishingTime", "07:30:00", "memo", "바람이 강했음"))))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/catch-reports/")))
				.andExpect(jsonPath("$.reportId").isNumber())
				.andExpect(jsonPath("$.catchStatus").value("NO_CATCH"))
				.andExpect(jsonPath("$.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.locationVisibility").value("PRIVATE"));

		insertReport(700, 456, "2026-09-21", "other");
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].memo").value("바람이 강했음"))
				.andExpect(jsonPath("$.items[0].waterType").value("SEA"))
				.andExpect(jsonPath("$.items[0].species.length()").value(0))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());

		assertThat(jdbc.queryForMap("SELECT * FROM fishingstory.catch_reports WHERE user_id=?", USER_ID))
				.containsEntry("catch_status", "NO_CATCH").containsEntry("visibility", "PRIVATE")
				.containsEntry("location_visibility", "PRIVATE");
	}

	@Test void createsCaughtReportAndProjectsCollectionInSameTransaction() throws Exception {
		String first = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-20",
						"species", java.util.List.of(Map.of("speciesId", 1003, "maxLengthCm", 32.5),
								Map.of("speciesId", 1001))))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.collectionEvents.length()").value(2))
				.andExpect(jsonPath("$.collectionEvents[0].type").value("NEW_SPECIES"))
				.andExpect(jsonPath("$.collectionEvents[0].speciesName").value("조피볼락"))
				.andExpect(jsonPath("$.collectionEvents[1].type").value("NEW_SPECIES"))
				.andReturn().getResponse().getContentAsString();
		long reportId = json.readTree(first).get("reportId").asLong();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_species WHERE report_id=?",
				Integer.class, reportId)).isEqualTo(2);
		assertThat(jdbc.queryForMap("SELECT max_length_cm, first_fishing_date, last_fishing_date FROM fishingstory.user_fish_collection WHERE user_id=? AND species_id=1003", USER_ID))
				.containsEntry("max_length_cm", new java.math.BigDecimal("32.5"));

		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-18",
						"species", java.util.List.of(Map.of("speciesId", 1003, "maxLengthCm", 40.0))))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.collectionEvents.length()").value(1))
				.andExpect(jsonPath("$.collectionEvents[0].type").value("NEW_MAX_LENGTH"))
				.andExpect(jsonPath("$.collectionEvents[0].maxLengthCm").value(40.0));
		assertThat(jdbc.queryForMap("SELECT max_length_cm, first_fishing_date, last_fishing_date FROM fishingstory.user_fish_collection WHERE user_id=? AND species_id=1003", USER_ID))
				.containsEntry("max_length_cm", new java.math.BigDecimal("40.0"))
				.containsEntry("first_fishing_date", java.sql.Date.valueOf("2026-09-18"))
				.containsEntry("last_fishing_date", java.sql.Date.valueOf("2026-09-20"));

		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].species[0].name").value("조피볼락"))
				.andExpect(jsonPath("$.items[0].species[1].name").value("감성돔"))
				.andExpect(jsonPath("$.items[1].species[0].name").value("조피볼락"));
	}

	@Test void sameRequestKeyReturnsTheSavedReportWithoutDuplicatingRowsOrCollection() throws Exception {
		String key = "0123456789abcdef0123456789abcdef";
		Map<String, Object> request = new java.util.HashMap<>(Map.of("catchStatus", "CAUGHT", "waterType", "SEA",
				"fishingDate", "2026-09-20", "fishingTime", "07:30:00", "memo", "첫 입질", "requestKey", key,
				"species", java.util.List.of(Map.of("speciesId", 1003, "maxLengthCm", 32.5),
						Map.of("customName", "기타 물고기"))));
		request.put("requestKey", "0123456789ABCDEF0123456789ABCDEF");
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isZero();

		request.put("requestKey", key);
		String body = json.writeValueAsString(request);
		String first = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.collectionEvents.length()").value(1))
				.andReturn().getResponse().getContentAsString();
		long reportId = json.readTree(first).get("reportId").asLong();
		var replay = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(header().string("Location", "/api/catch-reports/me/" + reportId))
				.andExpect(jsonPath("$.catchStatus").value("CAUGHT")).andExpect(jsonPath("$.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.collectionEvents.length()").value(0))
				.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(replay).get("reportId").asLong()).isEqualTo(reportId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_species", Integer.class)).isEqualTo(2);
		var collection = jdbc.queryForList("SELECT * FROM fishingstory.user_fish_collection");
		assertThat(collection).hasSize(1);

		// The same key never overwrites or duplicates a report when the content differs.
		for (var change : java.util.List.<Map<String, Object>>of(Map.of("memo", "다른 메모"),
				Map.of("fishingTime", "08:00:00"),
				Map.of("species", java.util.List.of(Map.of("speciesId", 1003, "maxLengthCm", 33.0),
						Map.of("customName", "기타 물고기"))),
				Map.of("species", java.util.List.of(Map.of("customName", "기타 물고기"),
						Map.of("speciesId", 1003, "maxLengthCm", 32.5))))) {
			var changed = new java.util.HashMap<>(request);
			changed.putAll(change);
			mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed)))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
		}
		assertThat(jdbc.queryForObject("SELECT memo FROM fishingstory.catch_reports WHERE report_id=?", String.class, reportId))
				.isEqualTo("첫 입질");
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.user_fish_collection")).isEqualTo(collection);

		// Keys are scoped to the member, and a new key creates a new report.
		String other = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + other)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		request.put("requestKey", "ffffffffffffffffffffffffffffffff");
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
				.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports WHERE user_id=?", Integer.class, USER_ID))
				.isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports WHERE user_id=456", Integer.class))
				.isEqualTo(1);
	}

	@Test void fractionalTimesSurvivePersistenceReplayAndContentUpdates() throws Exception {
		for (String time : java.util.List.of("12:34", "12:34:56", "12:34:56.123456",
				"12:34:56.123456789", "23:59:59.999999999")) {
			String key = UUID.randomUUID().toString().replace("-", "");
			var request = new java.util.LinkedHashMap<String, Object>(Map.of(
					"catchStatus", "NO_CATCH", "waterType", "SEA", "fishingDate", "2026-09-20",
					"fishingTime", time, "memo", "시간 유지", "requestKey", key));
			String body = json.writeValueAsString(request);
			String first = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			long id = json.readTree(first).get("reportId").asLong();
			java.time.LocalTime expected = java.time.LocalTime.parse(time)
					.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
			var stored = jdbc.queryForObject("SELECT fishing_time FROM fishingstory.catch_reports WHERE report_id=?",
					(result, row) -> result.getObject(1, java.time.LocalTime.class), id);
			assertThat(stored).isEqualTo(expected);
			String replay = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isCreated()).andExpect(jsonPath("$.collectionEvents").isEmpty())
					.andReturn().getResponse().getContentAsString();
			assertThat(json.readTree(replay).get("reportId").asLong()).isEqualTo(id);
			request.put("memo", "메모만 변경");
			mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
					.andExpect(status().isNoContent());
			String detail = mvc.perform(get("/api/catch-reports/me/" + id)
					.header("Authorization", "Bearer " + access)).andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertThat(java.time.LocalTime.parse(json.readTree(detail).get("fishingTime").asText())).isEqualTo(expected);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports WHERE request_key=?",
					Integer.class, key)).isEqualTo(1);
		}
	}

	@Test void concurrentRetriesWithOneRequestKeyCreateOneReport() throws Exception {
		String body = json.writeValueAsString(Map.of("catchStatus", "NO_CATCH", "waterType", "SEA",
				"fishingDate", "2026-09-20", "requestKey", "fedcba9876543210fedcba9876543210"));
		var start = new java.util.concurrent.CountDownLatch(1);
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
				start.await(10, java.util.concurrent.TimeUnit.SECONDS);
				var response = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
						.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
				assertThat(response.getStatus()).isEqualTo(201);
				return json.readTree(response.getContentAsString()).get("reportId").asLong();
			})).toList();
			start.countDown();
			assertThat(calls.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS))
					.isEqualTo(calls.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isEqualTo(1);
	}

	@Test void internalArgumentFailuresAreNotReportedAsInvalidInput() throws Exception {
		doThrow(new IllegalArgumentException("private detail"))
				.when(createService).create(anyLong(), any(), any(CreateCatchReportCommand.class));
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(validBody()))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private detail"))));
	}

	@Test void returnsWaterSpecificSpeciesAndSearchesAlias() throws Exception {
		mvc.perform(get("/api/fish-species").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("query", "우럭"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].speciesId").value(1003))
				.andExpect(jsonPath("$.items[0].name").value("조피볼락"));
		mvc.perform(get("/api/fish-species").header("Authorization", "Bearer " + access)
				.param("waterType", "FRESHWATER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].name").value("붕어"))
				.andExpect(jsonPath("$.items[1].name").value("잉어"));
		mvc.perform(get("/api/fish-species").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("query", "%"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void rejectsCaughtReportWithMissingDuplicateOrWrongWaterSpecies() throws Exception {
		for (Object species : java.util.List.of(
				java.util.List.of(),
				java.util.List.of(Map.of("speciesId", 1001), Map.of("speciesId", 1001)),
				java.util.List.of(Map.of("speciesId", 2001)),
				java.util.List.of(Map.of("speciesId", 1001, "maxLengthCm", 0)),
				java.util.List.of(Map.of("speciesId", 1001, "maxLengthCm", 10.25)))) {
			mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
							"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-20",
							"species", species))))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isZero();
	}

	@Test void listUsesStableOrderAndOpaqueCursor() throws Exception {
		insertReport(101, USER_ID, "2026-09-18", "old");
		insertReport(102, USER_ID, "2026-09-20", "newer-a");
		insertReport(103, USER_ID, "2026-09-20", "newer-b");
		jdbc.update("UPDATE fishingstory.catch_reports SET created_at='2026-09-20T01:00:00Z' WHERE report_id=102");
		jdbc.update("UPDATE fishingstory.catch_reports SET created_at='2026-09-20T02:00:00Z' WHERE report_id=103");

		var first = mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("limit", "2")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].reportId").value(103))
				.andExpect(jsonPath("$.items[1].reportId").value(102))
				.andExpect(jsonPath("$.nextCursor").isString()).andReturn().getResponse().getContentAsString();
		String cursor = json.readTree(first).get("nextCursor").asText();
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("limit", "2").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(101))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test void rejectsUnsupportedAndInvalidInputWithoutWriting() throws Exception {
		for (Map<String, String> body : java.util.List.of(
				Map.of("catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-20"),
				Map.of("catchStatus", "NO_CATCH", "waterType", "RIVER", "fishingDate", "2026-09-20"),
				Map.of("catchStatus", "NO_CATCH", "waterType", "SEA", "fishingDate",
						LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()),
				Map.of("catchStatus", "NO_CATCH", "waterType", "SEA", "fishingDate", "2026-09-20", "memo", "가".repeat(2001)))) {
			mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isZero();
	}

	@Test void enforcesCurrentAccountStateAndAuthentication() throws Exception {
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(validBody()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mvc.perform(get("/api/fish-species").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mvc.perform(post("/api/catch-reports").contentType(MediaType.APPLICATION_JSON).content(validBody()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/fish-species").param("waterType", "SEA"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test void rejectsMalformedCursorAndLimit() throws Exception {
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access).param("cursor", "not-a-cursor"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access).param("limit", "51"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test void detailUpdateAndDeleteRebuildCollectionFromRemainingReports() throws Exception {
		long first = createCaught("2026-09-18", 1003, 40.0);
		long second = createCaught("2026-09-20", 1003, 32.5);
		mvc.perform(get("/api/catch-reports/me/{id}", first).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.species[0].name").value("조피볼락"))
				.andExpect(jsonPath("$.species[0].maxLengthCm").value(40.0));

		mvc.perform(put("/api/catch-reports/me/{id}", first).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
					"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-21",
					"memo", "수정됨", "species", java.util.List.of(Map.of("speciesId", 1001, "maxLengthCm", 22.0))))))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/catch-reports/me/{id}", first).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.memo").value("수정됨"))
				.andExpect(jsonPath("$.species[0].speciesId").value(1001));
		assertThat(jdbc.queryForMap("SELECT max_length_cm, first_fishing_date, last_fishing_date FROM fishingstory.user_fish_collection WHERE user_id=? AND species_id=1003", USER_ID))
				.containsEntry("max_length_cm", new java.math.BigDecimal("32.5"))
				.containsEntry("first_fishing_date", java.sql.Date.valueOf("2026-09-20"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=? AND species_id=1001", Integer.class, USER_ID)).isEqualTo(1);

		mvc.perform(delete("/api/catch-reports/me/{id}", second).header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=? AND species_id=1003", Integer.class, USER_ID)).isZero();
		mvc.perform(delete("/api/catch-reports/me/{id}", first).header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=?", Integer.class, USER_ID)).isZero();
		mvc.perform(get("/api/catch-reports/me/{id}", first).header("Authorization", "Bearer " + access))
				.andExpect(status().isNotFound());
	}

	@Test void rejectsOtherUsersAndInvalidUpdatesWithoutChangingOriginal() throws Exception {
		long own = createCaught("2026-09-20", 1003, 32.5);
		insertReport(700, 456, "2026-09-20", "other");
		for (long id : new long[] {700, 999}) {
			mvc.perform(get("/api/catch-reports/me/{id}", id).header("Authorization", "Bearer " + access))
					.andExpect(status().isNotFound());
			mvc.perform(put("/api/catch-reports/me/{id}", id).header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(validBody()))
					.andExpect(status().isNotFound());
			mvc.perform(delete("/api/catch-reports/me/{id}", id).header("Authorization", "Bearer " + access))
					.andExpect(status().isNotFound());
		}
		mvc.perform(get("/api/catch-reports/me/{id}", own))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/catch-reports/me/{id}", own).header("Authorization", "Bearer " + access)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
				"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-21",
				"species", java.util.List.of(Map.of("speciesId", 2001))))))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/catch-reports/me/{id}", own).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.species[0].speciesId").value(1003));
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(get("/api/catch-reports/me/{id}", own).header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/catch-reports/me/{id}", own).header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden());
	}

	@Test void changingCaughtToNoCatchRemovesOnlyItsCollectionContribution() throws Exception {
		long caught = createCaught("2026-09-18", 1003, 40.0);
		mvc.perform(put("/api/catch-reports/me/{id}", caught).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
					"catchStatus", "NO_CATCH", "waterType", "FRESHWATER", "fishingDate", "2026-09-19"))))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/catch-reports/me/{id}", caught).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.catchStatus").value("NO_CATCH"))
				.andExpect(jsonPath("$.waterType").value("FRESHWATER"))
				.andExpect(jsonPath("$.species.length()").value(0));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=?", Integer.class, USER_ID)).isZero();
	}

	@Test void combinesListFiltersWithoutHidingOtherSelectedSpeciesOrLeakingOtherUsers() throws Exception {
		long own = createCaught("2026-09-20", 1003, 32.5);
		jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id, report_id, species_id, sort_order) VALUES (801, ?, 1001, 1)", own);
		insertReport(700, 456, "2026-09-21", "other");
		jdbc.update("UPDATE fishingstory.catch_reports SET catch_status='CAUGHT' WHERE report_id=700");
		jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id, report_id, species_id, sort_order) VALUES (802, 700, 1003, 0)");
		insertReport(701, USER_ID, "2026-09-21", "no catch");
		insertReport(702, USER_ID, "2026-09-19", "freshwater");
		jdbc.update("UPDATE fishingstory.catch_reports SET water_type='FRESHWATER' WHERE report_id=702");

		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("catchStatus", "CAUGHT").param("speciesId", "1003"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(own))
				.andExpect(jsonPath("$.items[0].species.length()").value(2))
				.andExpect(jsonPath("$.items[0].species[1].speciesId").value(1001))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("waterType", "FRESHWATER").param("catchStatus", "NO_CATCH"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(702));
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("catchStatus", "NO_CATCH"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
	}

	@Test void filteredCursorSkipsNonmatchingRowsAndUsesIdToBreakTies() throws Exception {
		long first = createCaught("2026-09-20", 1003, 32.5);
		long second = createCaught("2026-09-20", 1003, 40.0);
		createCaught("2026-09-21", 1001, 20.0);
		insertReport(701, USER_ID, "2026-09-21", "no catch");
		jdbc.update("UPDATE fishingstory.catch_reports SET created_at='2026-09-20T01:00:00Z' WHERE report_id IN (?, ?)", first, second);
		String body = mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("speciesId", "1003").param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(Math.max(first, second)))
				.andExpect(jsonPath("$.nextCursor").isString()).andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("speciesId", "1003").param("limit", "1")
				.param("cursor", json.readTree(body).get("nextCursor").asText()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(Math.min(first, second)))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test void filtersRetainInactiveSpeciesHistoryAndReturnEmptyForNonmatchingCombinations() throws Exception {
		long own = createCaught("2026-09-20", 1003, 32.5);
		jdbc.update("UPDATE fishingstory.fish_species SET active=false WHERE species_id=1003");
		try {
			mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
					.param("speciesId", "1003"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].reportId").value(own));
		} finally {
			jdbc.update("UPDATE fishingstory.fish_species SET active=true WHERE species_id=1003");
		}
		for (Map<String, String> filter : java.util.List.of(
				Map.of("speciesId", "999999"), Map.of("speciesId", "2001", "waterType", "SEA"),
				Map.of("speciesId", "1003", "catchStatus", "NO_CATCH"))) {
			var request = get("/api/catch-reports/me").header("Authorization", "Bearer " + access);
			filter.forEach((key, value) -> request.param(key, value));
			mvc.perform(request).andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(0))
					.andExpect(jsonPath("$.nextCursor").doesNotExist());
		}
	}

	@Test void rejectsInvalidFiltersAndRequiresAnActiveAuthenticatedOwner() throws Exception {
		for (Map<String, String> filter : java.util.List.of(
				Map.of("waterType", "BOTH"), Map.of("catchStatus", "caught"),
				Map.of("speciesId", "0"), Map.of("speciesId", "-1"), Map.of("speciesId", "fish"),
				Map.of("speciesId", "9223372036854775808"))) {
			var request = get("/api/catch-reports/me").header("Authorization", "Bearer " + access);
			filter.forEach((key, value) -> request.param(key, value));
			mvc.perform(request).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		mvc.perform(get("/api/catch-reports/me").param("speciesId", "1003"))
				.andExpect(status().isUnauthorized());
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("speciesId", "1003"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test void storesCustomSpeciesInOrderWithoutAddingToMasterOrCollection() throws Exception {
		String body = json.writeValueAsString(Map.of("catchStatus", "CAUGHT", "waterType", "SEA",
				"fishingDate", "2026-09-20", "species", java.util.List.of(
						Map.of("customName", "  직접 기록한 물고기  ", "maxLengthCm", 23.5),
						Map.of("speciesId", 1001))));
		String result = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.collectionEvents.length()").value(1))
				.andReturn().getResponse().getContentAsString();
		long id = json.readTree(result).get("reportId").asLong();
		mvc.perform(get("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.species.length()").value(2))
				.andExpect(jsonPath("$.species[0].speciesId").doesNotExist())
				.andExpect(jsonPath("$.species[0].name").value("직접 기록한 물고기"))
				.andExpect(jsonPath("$.species[0].maxLengthCm").value(23.5))
				.andExpect(jsonPath("$.species[1].speciesId").value(1001));
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access)
				.param("speciesId", "1001"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].species.length()").value(2));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fish_species WHERE name=?",
				Integer.class, "직접 기록한 물고기")).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=?",
				Integer.class, USER_ID)).isEqualTo(1);
		String otherAccess = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		mvc.perform(get("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + otherAccess))
				.andExpect(status().isNotFound());
	}

	@Test void switchesBetweenMasterCustomAndNoCatchAndRebuildsOnlyMasterCollection() throws Exception {
		long id = createCaught("2026-09-20", 1001, 30.0);
		String custom = json.writeValueAsString(Map.of("catchStatus", "CAUGHT", "waterType", "SEA",
				"fishingDate", "2026-09-20", "species", java.util.List.of(Map.of("customName", "감성돔"))));
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(custom)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isZero();
		mvc.perform(get("/api/catch-reports/me").header("Authorization", "Bearer " + access).param("speciesId", "1001"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		String master = json.writeValueAsString(Map.of("catchStatus", "CAUGHT", "waterType", "SEA",
				"fishingDate", "2026-09-20", "species", java.util.List.of(Map.of("speciesId", 1001))));
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(master)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isEqualTo(1);
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(custom)).andExpect(status().isNoContent());
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"catchStatus", "NO_CATCH", "waterType", "SEA", "fishingDate", "2026-09-20"))))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_species", Integer.class)).isZero();
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(custom)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_species", Integer.class)).isZero();
	}

	@Test void rejectsAmbiguousEmptyDuplicateAndOverlongCustomSpeciesWithoutSaving() throws Exception {
		for (var selection : java.util.List.of(
				java.util.List.of(Map.of("speciesId", 1001, "customName", "기타")),
				java.util.List.of(Map.of("maxLengthCm", 20)),
				java.util.List.of(Map.of("customName", "   ")),
				java.util.List.of(Map.of("customName", "어".repeat(101))),
				java.util.List.of(Map.of("customName", "물\n고기")),
				java.util.List.of(Map.of("customName", "물고기", "maxLengthCm", 0)),
				java.util.List.of(Map.of("customName", "가"), Map.of("customName", "\u1100\u1161")),
				java.util.List.of(Map.of("customName", "물고기"), Map.of("customName", " 물고기 ")))) {
			mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
							"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-20", "species", selection))))
					.andExpect(status().isBadRequest());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isZero();
	}

	@Test void createsCustomOnlyReportWithoutCollectionEvents() throws Exception {
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"catchStatus", "CAUGHT", "waterType", "FRESHWATER", "fishingDate", "2026-09-20",
						"species", java.util.List.of(Map.of("customName", "새".repeat(100)))))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.collectionEvents.length()").value(0));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isZero();
	}

	@Test void filtersPublicReportsWithAndSemanticsAndPreservesAllSpeciesAcrossCursorPages() throws Exception {
		insertReport(710, USER_ID, "2026-09-24", "sea");
		insertReport(711, USER_ID, "2026-09-23", "freshwater");
		insertReport(712, USER_ID, "2026-09-22", "private");
		insertReport(713, 456, "2026-09-21", "other public");
		jdbc.update("UPDATE fishingstory.catch_reports SET catch_status='CAUGHT', visibility='PUBLIC' WHERE report_id IN (710,711,712,713)");
		jdbc.update("UPDATE fishingstory.catch_reports SET visibility='PRIVATE' WHERE report_id=712");
		jdbc.update("UPDATE fishingstory.catch_reports SET water_type='FRESHWATER' WHERE report_id=711");
		jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id,report_id,species_id,sort_order) VALUES (810,710,1003,0),(811,710,1001,1),(812,711,1003,0),(813,712,1003,0),(814,713,1003,0)");
		var response = mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("catchStatus", "CAUGHT").param("speciesId", "1003").param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].reportId").value(710))
				.andExpect(jsonPath("$.items[0].species.length()").value(2)).andReturn().getResponse().getContentAsString();
		String cursor = json.readTree(response).get("nextCursor").asString();
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("catchStatus", "CAUGHT").param("speciesId", "1003").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(713));
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=456");
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("speciesId", "1003").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + access)
				.param("catchStatus", "NO_CATCH").param("speciesId", "1003"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test void rejectsInvalidPublicFilters() throws Exception {
		for (var params : java.util.List.of(Map.of("waterType", "BOTH"), Map.of("catchStatus", "unknown"),
				Map.of("speciesId", "0"), Map.of("speciesId", "-1"), Map.of("speciesId", "not-a-number"))) {
			var request = get("/api/catch-reports").header("Authorization", "Bearer " + access);
			params.forEach(request::param);
			mvc.perform(request).andExpect(status().isBadRequest());
		}
	}

	@Test void publicVisibilityRoundTripPreservesProjectionAndRevokesReads() throws Exception {
		long id = createCaught("2026-09-20", 1003, 32.5);
		String viewer = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		var collection = jdbc.queryForList("SELECT * FROM fishingstory.user_fish_collection");
		var species = jdbc.queryForList("SELECT * FROM fishingstory.catch_report_species");
		mvc.perform(get("/api/catch-reports/" + id).header("Authorization", "Bearer " + viewer))
				.andExpect(status().isNotFound());
		setVisibility(id, "PUBLIC");
		var changed = jdbc.queryForMap("SELECT * FROM fishingstory.catch_reports WHERE report_id=?", id);
		setVisibility(id, "PUBLIC");
		assertThat(jdbc.queryForMap("SELECT * FROM fishingstory.catch_reports WHERE report_id=?", id)).isEqualTo(changed);
		mvc.perform(get("/api/catch-reports/" + id).header("Authorization", "Bearer " + viewer))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.visibility").value("PUBLIC"))
				.andExpect(jsonPath("$.authorNickname").value("Fisher"))
				.andExpect(jsonPath("$.species[0].name").value("조피볼락"))
				.andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist())
				.andExpect(jsonPath("$.locationVisibility").doesNotExist()).andExpect(jsonPath("$.images").doesNotExist());
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + viewer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		setVisibility(id, "PRIVATE");
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.user_fish_collection")).isEqualTo(collection);
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.catch_report_species")).isEqualTo(species);
		assertThat(jdbc.queryForMap("SELECT location_visibility FROM fishingstory.catch_reports WHERE report_id=?", id))
				.containsEntry("location_visibility", "PRIVATE");
		mvc.perform(get("/api/catch-reports/" + id).header("Authorization", "Bearer " + viewer))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + viewer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		setVisibility(id, "PUBLIC");
		mvc.perform(delete("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/catch-reports/" + id).header("Authorization", "Bearer " + viewer))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + viewer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void publicDoesNotGrantOwnershipAndRequiresActiveViewer() throws Exception {
		insertReport(700, USER_ID, "2026-09-20", "visible");
		String other = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		for (String visibility : java.util.List.of("PRIVATE", "PUBLIC")) {
			setVisibility(700, visibility);
			mvc.perform(patch("/api/catch-reports/me/700/visibility").header("Authorization", "Bearer " + other)
					.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
					.andExpect(status().isNotFound());
			mvc.perform(put("/api/catch-reports/me/700").header("Authorization", "Bearer " + other)
					.contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isNotFound());
			mvc.perform(delete("/api/catch-reports/me/700").header("Authorization", "Bearer " + other))
					.andExpect(status().isNotFound());
			mvc.perform(get("/api/catch-reports/me/700").header("Authorization", "Bearer " + other))
					.andExpect(status().isNotFound());
		}
		mvc.perform(get("/api/catch-reports")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/catch-reports/700")).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/catch-reports/me/700/visibility").contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"PRIVATE\"}")).andExpect(status().isUnauthorized());
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=456");
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/catch-reports/700").header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden());
	}

	@Test void rejectsInvalidVisibilityAndPreservesPublicOnContentUpdate() throws Exception {
		insertReport(700, USER_ID, "2026-09-20", "visible");
		for (String body : java.util.List.of("{}", "{\"visibility\":null}", "{\"visibility\":\"REGION\"}", "{\"visibility\":\"public\"}")) {
			mvc.perform(patch("/api/catch-reports/me/700/visibility").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		setVisibility(700, "PUBLIC");
		mvc.perform(put("/api/catch-reports/me/700").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isNoContent());
		mvc.perform(get("/api/catch-reports/700").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"));
		mvc.perform(get("/api/catch-reports/me/700").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"));
	}

	@Test void publicCursorExcludesPrivateAndWithdrawnAuthorsAndRechecksNextPage() throws Exception {
		for (long id : java.util.List.of(700L, 701L, 702L, 703L)) insertReport(id, USER_ID, "2026-09-20", "report " + id);
		jdbc.update("UPDATE fishingstory.catch_reports SET created_at='2026-09-20T00:00:00Z'");
		for (long id : java.util.List.of(700L, 701L, 702L)) setVisibility(id, "PUBLIC");
		String body = mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + access).param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].reportId").value(702))
				.andReturn().getResponse().getContentAsString();
		String cursor = json.readTree(body).get("nextCursor").asText();
		setVisibility(701, "PRIVATE");
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + access).param("limit", "1").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(700)).andExpect(jsonPath("$.nextCursor").doesNotExist());
		String viewer = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + viewer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get("/api/catch-reports/700").header("Authorization", "Bearer " + viewer))
				.andExpect(status().isNotFound());
		mvc.perform(patch("/api/catch-reports/me/700/visibility").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isForbidden());
		for (String limit : java.util.List.of("0", "51")) mvc.perform(get("/api/catch-reports")
				.header("Authorization", "Bearer " + viewer).param("limit", limit)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/catch-reports").header("Authorization", "Bearer " + viewer).param("cursor", "broken"))
				.andExpect(status().isBadRequest());
	}

	@Test void publicCustomSpeciesKeepTheirNamesWithoutCreatingCollectionEntries() throws Exception {
		String body = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", "2026-09-20",
						"species", java.util.List.of(Map.of("customName", "기타 물고기", "maxLengthCm", 21.5))))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = json.readTree(body).get("reportId").asLong();
		setVisibility(id, "PUBLIC");
		String viewer = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		mvc.perform(get("/api/catch-reports/" + id).header("Authorization", "Bearer " + viewer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.species[0].speciesId").doesNotExist())
				.andExpect(jsonPath("$.species[0].name").value("기타 물고기"))
				.andExpect(jsonPath("$.species[0].maxLengthCm").value(21.5));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isZero();
	}

	@Test void rejectsNulMemoOnCreateAndUpdateWithoutChangingReportsOrCollection() throws Exception {
		long id = createCaught("2026-09-20", 1003, 32.5);
		var reports = jdbc.queryForList("SELECT * FROM fishingstory.catch_reports");
		var species = jdbc.queryForList("SELECT * FROM fishingstory.catch_report_species");
		var collection = jdbc.queryForList("SELECT * FROM fishingstory.user_fish_collection");
		for (String memo : java.util.List.of("\0본문", "본\0문", "본문\0")) {
			String body = json.writeValueAsString(Map.of("catchStatus", "NO_CATCH", "waterType", "SEA",
					"fishingDate", "2026-09-20", "memo", memo));
			mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.catch_reports")).isEqualTo(reports);
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.catch_report_species")).isEqualTo(species);
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.user_fish_collection")).isEqualTo(collection);
	}

	@Test void rejectsNulSpeciesSearchAsInvalidInput() throws Exception {
		mvc.perform(get("/api/fish-species").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("query", "우\0럭"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/fish-species").header("Authorization", "Bearer " + access)
				.param("waterType", "SEA").param("query", "우럭"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].speciesId").value(1003));
	}

	@Test void optionalWeightAndCountRebuildAcrossCustomConversionAndDeletion() throws Exception {
		long old = createCaught("2026-09-19", 1003, 30);
		String body = """
			{"catchStatus":"CAUGHT","waterType":"SEA","fishingDate":"2026-09-20",
			 "requestKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			 "species":[{"speciesId":1003,"maxWeightG":510.5},{"customName":"직접 물고기","maxWeightG":20}]}
			""";
		String saved = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.collectionEvents[0].type").value("NEW_MAX_WEIGHT"))
				.andReturn().getResponse().getContentAsString();
		long id = json.readTree(saved).get("reportId").asLong();
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.reportId").value(id)).andExpect(jsonPath("$.collectionEvents.length()").value(0));
		mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body.replace("510.5", "511.5"))).andExpect(status().isConflict());
		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.reportCount").value(2))
				.andExpect(jsonPath("$.maxLengthCm").value(30)).andExpect(jsonPath("$.maxWeightG").value(510.5));
		mvc.perform(get("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.species[1].name").value("직접 물고기"))
				.andExpect(jsonPath("$.species[1].maxWeightG").value(20));
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body.replace("\"speciesId\":1003", "\"customName\":\"바뀐 기록\"")))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.reportCount").value(1))
				.andExpect(jsonPath("$.maxWeightG").doesNotExist());
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/catch-reports/me/" + old).header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.reportCount").value(1))
				.andExpect(jsonPath("$.maxLengthCm").doesNotExist()).andExpect(jsonPath("$.maxWeightG").value(510.5));
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isZero();
	}

	@Test void rejectsInvalidWeightWithoutWritingRecords() throws Exception {
		for (String value : java.util.List.of("0", "-1", "1.01", "2000000.1", "100000000000")) {
			mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
					.contentType(MediaType.APPLICATION_JSON).content("""
					{"catchStatus":"CAUGHT","waterType":"SEA","fishingDate":"2026-09-20",
					"species":[{"speciesId":1003,"maxWeightG":%s}]}
					""".formatted(value))).andExpect(status().isBadRequest());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_reports", Integer.class)).isZero();
	}

	@Test void acceptsWeightUpToTwoTonnesOnCreateAndRejectsMoreOnUpdate() throws Exception {
		String body = """
				{"catchStatus":"CAUGHT","waterType":"SEA","fishingDate":"2026-09-20",
				"species":[{"speciesId":1003,"maxWeightG":%s}]}
				""";
		String created = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body.formatted("2000000.0")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = json.readTree(created).get("reportId").asLong();
		mvc.perform(put("/api/catch-reports/me/" + id).header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(body.formatted("2000000.1")))
				.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT max_weight_g FROM fishingstory.catch_report_species", java.math.BigDecimal.class))
				.isEqualByComparingTo("2000000.0");
		assertThat(jdbc.queryForObject("SELECT max_weight_g FROM fishingstory.user_fish_collection", java.math.BigDecimal.class))
				.isEqualByComparingTo("2000000.0");
	}

	private void setVisibility(long id, String visibility) throws Exception {
		mvc.perform(patch("/api/catch-reports/me/" + id + "/visibility").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("visibility", visibility))))
				.andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
	}

	private long createCaught(String date, long speciesId, double length) throws Exception {
		String body = mvc.perform(post("/api/catch-reports").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
					"catchStatus", "CAUGHT", "waterType", "SEA", "fishingDate", date,
					"species", java.util.List.of(Map.of("speciesId", speciesId, "maxLengthCm", length))))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("reportId").asLong();
	}

	private String validBody() throws Exception {
		return json.writeValueAsString(Map.of("catchStatus", "NO_CATCH", "waterType", "SEA", "fishingDate", "2026-09-20"));
	}

	private void insertReport(long reportId, long userId, String date, String memo) {
		jdbc.update("""
				INSERT INTO fishingstory.catch_reports
				(report_id, user_id, catch_status, water_type, fishing_date, memo)
				VALUES (?, ?, 'NO_CATCH', 'SEA', ?::date, ?)
				""", reportId, userId, date, memo);
	}

	private void insertMember(long userId, String nickname, String email) {
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at) VALUES (?, ?, lower(?), now())",
				userId, nickname, nickname);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', ?, 'test-hash', now())",
				userId + 1000, userId, email);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
