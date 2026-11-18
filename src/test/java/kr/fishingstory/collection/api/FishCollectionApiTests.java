package kr.fishingstory.collection.api;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.collection.query.infrastructure.mybatis.FishCollectionQueryMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FishCollectionApiTests.DatabaseConfiguration.class)
class FishCollectionApiTests {
	static final long USER_ID = 9007199254740993L;
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired PlatformTransactionManager transactions;
	@Autowired SqlSessionTemplate sql;
	@MockitoSpyBean FishCollectionQueryMapper collection;
	String access;

	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_fish_collection");
		jdbc.update("DELETE FROM fishingstory.catch_report_species");
		jdbc.update("DELETE FROM fishingstory.catch_reports");
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("UPDATE fishingstory.fish_species SET active=true");
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at) VALUES (?, 'Fisher', 'fisher', now())", USER_ID);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', 'fisher@example.com', 'test-hash', now())",
				USER_ID + 1000, USER_ID);
		access = tokens.issue(USER_ID, "USER", UUID.randomUUID().toString()).value();
	}

	@Test void rejectsNulSearchAsInvalidInputAndKeepsNormalSearchAvailable() throws Exception {
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("query", "우\0럭"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("query", "우럭"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].speciesId").value(1003));
	}

	@Test void returnsAcquiredDetailAndOnlyOwnRecentReports() throws Exception {
		insertCollection(1, 1003, "42.5");
		insertReport(10, USER_ID, 1003, "2026-09-20", "42.5", "최근 기록");
		insertReport(11, USER_ID, 1003, "2026-09-10", "32.0", "첫 기록");
		insertReport(12, 456, 1003, "2026-09-21", "99.0", "타인 기록");

		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.name").value("조피볼락"))
				.andExpect(jsonPath("$.acquired").value(true))
				.andExpect(jsonPath("$.maxLengthCm").value(42.5))
				.andExpect(jsonPath("$.firstFishingDate").value("2026-09-10"))
				.andExpect(jsonPath("$.lastFishingDate").value("2026-09-20"))
				.andExpect(jsonPath("$.reports.length()").value(2))
				.andExpect(jsonPath("$.reports[0].reportId").value(10))
				.andExpect(jsonPath("$.reports[1].reportId").value(11));

		mvc.perform(get("/api/fish-collection/me/2001").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.acquired").value(false))
				.andExpect(jsonPath("$.maxLengthCm").doesNotExist())
				.andExpect(jsonPath("$.firstFishingDate").doesNotExist())
				.andExpect(jsonPath("$.reports.length()").value(0));
	}

	@Test void returnsProgressAndFiltersAcquiredCollectionByAlias() throws Exception {
		insertCollection(1, 1003, "42.5");
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.acquiredCount").value(1))
				.andExpect(jsonPath("$.totalCount").value(7))
				.andExpect(jsonPath("$.items.length()").value(7))
				.andExpect(jsonPath("$.items[2].name").value("조피볼락"))
				.andExpect(jsonPath("$.items[2].acquired").value(true))
				.andExpect(jsonPath("$.items[2].maxLengthCm").value(42.5));

		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("status", "ACQUIRED").param("waterType", "SEA").param("query", "우럭"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].speciesId").value(1003));
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("status", "UNACQUIRED").param("waterType", "FRESHWATER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].name").value("붕어"))
				.andExpect(jsonPath("$.items[1].name").value("잉어"));
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("query", "%"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void preservesAcquiredInactiveSpeciesButExcludesItFromProgress() throws Exception {
		insertCollection(1, 1003, null);
		jdbc.update("UPDATE fishingstory.fish_species SET active=false WHERE species_id IN (1003, 1005)");
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.acquiredCount").value(0))
				.andExpect(jsonPath("$.totalCount").value(5))
				.andExpect(jsonPath("$.items.length()").value(6))
				.andExpect(jsonPath("$.items[2].active").value(false))
				.andExpect(jsonPath("$.items[2].acquired").value(true));
		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.acquired").value(true));
		mvc.perform(get("/api/fish-collection/me/1005").header("Authorization", "Bearer " + access))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test void enforcesAuthenticationAccountStateAndFilters() throws Exception {
		mvc.perform(get("/api/fish-collection/me"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/fish-collection/me/1003"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("status", "UNKNOWN"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection", Integer.class)).isZero();
	}

	@Test void progressAndItemsShareSnapshotWhenCollectionIsDeletedBetweenQueries() throws Exception {
		insertCollection(1, 1003, "42.5");
		var changed = new AtomicBoolean();
		doAnswer(call -> {
			var result = sql.getMapper(FishCollectionQueryMapper.class).progress(USER_ID);
			if (changed.compareAndSet(false, true)) {
				commitSeparately(() -> jdbc.update("DELETE FROM fishingstory.user_fish_collection WHERE user_id=?", USER_ID));
			}
			return result;
		}).when(collection).progress(USER_ID);

		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("status", "ACQUIRED"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.acquiredCount").value(1))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].speciesId").value(1003));
		assertThat(changed).isTrue();
		mvc.perform(get("/api/fish-collection/me").header("Authorization", "Bearer " + access)
				.param("status", "ACQUIRED"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.acquiredCount").value(0))
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void detailAndReportsShareSnapshotWhenLastReportIsDeletedBetweenQueries() throws Exception {
		insertCollection(1, 1003, "42.5");
		insertReport(10, USER_ID, 1003, "2026-09-20", "42.5", "삭제 전 기록");
		var changed = new AtomicBoolean();
		doAnswer(call -> {
			var result = sql.getMapper(FishCollectionQueryMapper.class).findDetail(USER_ID, 1003);
			if (changed.compareAndSet(false, true)) {
				commitSeparately(() -> {
					jdbc.update("DELETE FROM fishingstory.catch_report_species WHERE report_id=10");
					jdbc.update("DELETE FROM fishingstory.catch_reports WHERE report_id=10");
					jdbc.update("DELETE FROM fishingstory.user_fish_collection WHERE user_id=?", USER_ID);
				});
			}
			return result;
		}).when(collection).findDetail(USER_ID, 1003);

		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.acquired").value(true))
				.andExpect(jsonPath("$.maxLengthCm").value(42.5))
				.andExpect(jsonPath("$.reports.length()").value(1))
				.andExpect(jsonPath("$.reports[0].reportId").value(10));
		assertThat(changed).isTrue();
		mvc.perform(get("/api/fish-collection/me/1003").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.acquired").value(false))
				.andExpect(jsonPath("$.reports.length()").value(0));
	}

	private void commitSeparately(Runnable change) {
		var transaction = new TransactionTemplate(transactions);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.executeWithoutResult(status -> change.run());
	}

	private void insertCollection(long id, long speciesId, String length) {
		jdbc.update("""
				INSERT INTO fishingstory.user_fish_collection
				(collection_id, user_id, species_id, max_length_cm, first_fishing_date, last_fishing_date)
				VALUES (?, ?, ?, ?::numeric, '2026-09-10', '2026-09-20')
				""", id, USER_ID, speciesId, length);
	}

	private void insertReport(long reportId, long userId, long speciesId, String date,
			String length, String memo) {
		jdbc.update("""
				INSERT INTO fishingstory.catch_reports
				(report_id, user_id, catch_status, water_type, fishing_date, memo)
				VALUES (?, ?, 'CAUGHT', 'SEA', ?::date, ?)
				""", reportId, userId, date, memo);
		jdbc.update("""
				INSERT INTO fishingstory.catch_report_species
				(report_species_id, report_id, species_id, max_length_cm, sort_order)
				VALUES (?, ?, ?, ?::numeric, 0)
				""", reportId + 100, reportId, speciesId, length);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5")
					.asCompatibleSubstituteFor("postgres"));
		}
	}
}
