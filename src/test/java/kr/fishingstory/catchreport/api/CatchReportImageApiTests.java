package kr.fishingstory.catchreport.api;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.catchreport.image.ImageStorage;
import kr.fishingstory.catchreport.image.CatchReportImageCleanup;
import kr.fishingstory.catchreport.image.CatchReportImageService;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CatchReportImageApiTests.DatabaseConfiguration.class)
class CatchReportImageApiTests {
	static final long USER_ID = 9007199254740993L;
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ObjectMapper json;
	@Autowired CatchReportImageCleanup cleanup;
	@Autowired CatchReportImageService imageService;
	@Autowired PlatformTransactionManager transactionManager;
	@MockitoBean ImageStorage storage;
	String access;

	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.catch_report_images");
		jdbc.update("DELETE FROM fishingstory.user_fish_collection");
		jdbc.update("DELETE FROM fishingstory.catch_report_species");
		jdbc.update("DELETE FROM fishingstory.catch_reports");
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.user_auth");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at) VALUES (?, 'Fisher', 'fisher', now())", USER_ID);
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (?, ?, 'EMAIL', 'fisher@example.com', 'hash', now())", USER_ID + 1, USER_ID);
		jdbc.update("INSERT INTO fishingstory.catch_reports (report_id, user_id, catch_status, water_type, fishing_date) VALUES (101, ?, 'NO_CATCH', 'SEA', '2026-09-20')", USER_ID);
		jdbc.update("INSERT INTO fishingstory.catch_reports (report_id, user_id, catch_status, water_type, fishing_date) VALUES (102, 456, 'NO_CATCH', 'SEA', '2026-09-20')");
		access = tokens.issue(USER_ID, "USER", UUID.randomUUID().toString()).value();
		when(storage.uploadUrl(anyString(), any())).thenReturn("https://r2.example.test/upload");
		when(storage.downloadUrl(anyString(), any())).thenReturn("https://r2.example.test/read");
		when(storage.inspect(anyString(), anyLong(), anyInt()))
				.thenReturn(new ImageStorage.Metadata(1000, "image/jpeg", 600, 400, "\"etag\""));
	}

	@Test void reservesCompletesListsAndRemovesOwnImage() throws Exception {
		String body = mvc.perform(post("/api/catch-reports/me/101/images")
				.header("Authorization", "Bearer " + access))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.masterUploadUrl").isString())
				.andReturn().getResponse().getContentAsString();
		long imageId = json.readTree(body).get("imageId").asLong();
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		verify(storage).promote(anyString(), contains("/master.jpg"), eq("\"etag\""));
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].displayOrder").value(1))
				.andExpect(jsonPath("$.items[0].thumbnailUrl").isString());
		mvc.perform(delete("/api/catch-reports/me/101/images/{imageId}", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, imageId))
				.isEqualTo("DELETE_PENDING");
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void publishingReportDoesNotExposePrivatePhotosToOtherMembers() throws Exception {
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at) VALUES (456, 'Other', 'other', now())");
		jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id, user_id, provider, email, password_hash, email_verified_at) VALUES (457, 456, 'EMAIL', 'other@example.com', 'hash', now())");
		String body = mvc.perform(post("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long imageId = json.readTree(body).get("imageId").asLong();
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		mvc.perform(patch("/api/catch-reports/me/101/visibility").header("Authorization", "Bearer " + access)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
				.andExpect(status().isNoContent());
		String other = tokens.issue(456, "USER", UUID.randomUUID().toString()).value();
		mvc.perform(get("/api/catch-reports/101").header("Authorization", "Bearer " + other))
				.andExpect(status().isOk()).andExpect(jsonPath("$.images").doesNotExist());
		clearInvocations(storage);
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + other))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/catch-reports/me/101/images/{imageId}", imageId).header("Authorization", "Bearer " + other))
				.andExpect(status().isNotFound());
		verifyNoInteractions(storage);
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test void rejectsOtherReportAndInvalidUploadedImage() throws Exception {
		mvc.perform(post("/api/catch-reports/me/102/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isNotFound());
		String body = mvc.perform(post("/api/catch-reports/me/101/images")
				.header("Authorization", "Bearer " + access)).andReturn().getResponse().getContentAsString();
		long imageId = json.readTree(body).get("imageId").asLong();
		when(storage.inspect(anyString(), anyLong(), anyInt()))
				.thenThrow(new IllegalArgumentException("bad jpeg"));
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, imageId))
				.isEqualTo("PENDING");
		mvc.perform(get("/api/catch-reports/me/101/images"))
				.andExpect(status().isUnauthorized());
	}

	@Test void deletingReportRetainsCleanupWorkForReadyImages() throws Exception {
		String body = mvc.perform(post("/api/catch-reports/me/101/images")
				.header("Authorization", "Bearer " + access)).andReturn().getResponse().getContentAsString();
		long imageId = json.readTree(body).get("imageId").asLong();
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/catch-reports/me/101").header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, imageId))
				.isEqualTo("DELETE_PENDING");
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isNotFound());
	}

	@Test void reordersOnlyTheExactOwnedReadySet() throws Exception {
		long first = reserveAndComplete();
		long second = reserveAndComplete();
		mvc.perform(put("/api/catch-reports/me/101/images/order").header("Authorization", "Bearer " + access)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(java.util.Map.of("imageIds", java.util.List.of(second, first)))))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].imageId").value(second))
				.andExpect(jsonPath("$.items[1].imageId").value(first));
		mvc.perform(put("/api/catch-reports/me/101/images/order").header("Authorization", "Bearer " + access)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(java.util.Map.of("imageIds", java.util.List.of(first, first)))))
				.andExpect(status().isBadRequest());
	}

	@Test void limitsReservationsToTenAndBlocksPendingAccount() throws Exception {
		for (int index = 0; index < 10; index++) {
			mvc.perform(post("/api/catch-reports/me/101/images")
					.header("Authorization", "Bearer " + access)).andExpect(status().isCreated());
		}
		mvc.perform(post("/api/catch-reports/me/101/images")
				.header("Authorization", "Bearer " + access))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=?", USER_ID);
		mvc.perform(get("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden());
	}

	@Test void concurrentKeyedReservationsRecoverTheLastSlotWithoutExtendingItsDeadline() throws Exception {
		for (int i = 0; i < 9; i++) imageService.reserve(USER_ID, 101);
		String key = "a".repeat(32);
		var start = new CyclicBarrier(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return imageService.reserve(USER_ID, 101, key); });
			var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return imageService.reserve(USER_ID, 101, key); });
			var original = first.get(20, TimeUnit.SECONDS);
			var replay = second.get(20, TimeUnit.SECONDS);
			assertThat(replay.imageId()).isEqualTo(original.imageId());
			assertThat(replay.uploadUrlExpiresAt()).isEqualTo(original.uploadUrlExpiresAt());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images WHERE report_id=101", Integer.class))
					.isEqualTo(10);
		}
	}

	@Test void completionRetryDoesNotCopyAgainOrChangeDisplayOrder() throws Exception {
		long first = reserveAndComplete();
		long second = reserveAndComplete();
		imageService.reorder(USER_ID, 101, java.util.List.of(second, first));
		expire(first);
		clearInvocations(storage);
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", first)
				.header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
		verifyNoInteractions(storage);
		assertThat(jdbc.queryForObject("SELECT display_order FROM fishingstory.catch_report_images WHERE image_id=?", Integer.class, first))
				.isEqualTo(2);
		mvc.perform(post("/api/catch-reports/me/102/images/{imageId}/complete", first)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNotFound());
	}

	@Test void failedTransactionKeepsTemporaryObjectsForCompletionRetry() throws Exception {
		long imageId = reserve();
		clearInvocations(storage);
		var transaction = new TransactionTemplate(transactionManager);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			imageService.complete(USER_ID, 101, imageId);
			throw new IllegalStateException("simulate transaction rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, imageId))
				.isEqualTo("PENDING");
		verify(storage, never()).delete(anyString());
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, imageId))
				.isEqualTo("READY");
		verify(storage, never()).delete(anyString());
	}

	@Test void partialPromotionFailureCanRetryWithoutLosingPendingUpload() throws Exception {
		long imageId = reserve();
		doThrow(new IllegalStateException("storage unavailable"))
				.doNothing().when(storage).promote(anyString(), endsWith("/thumbnail.jpg"), anyString());
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, imageId))
				.isEqualTo("PENDING");
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		verify(storage, never()).delete(anyString());
	}

	@Test void inspectionTimeoutKeepsReservationPendingAndASecondRequestCanComplete() throws Exception {
		long imageId = reserve();
		when(storage.inspect(anyString(), anyLong(), anyInt()))
				.thenThrow(software.amazon.awssdk.core.exception.ApiCallTimeoutException.create(4000))
				.thenReturn(new ImageStorage.Metadata(1000, "image/jpeg", 600, 400, "\"etag\""));
		clearInvocations(storage);
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		assertThat(jdbc.queryForMap("SELECT status, display_order FROM fishingstory.catch_report_images WHERE image_id=?", imageId))
				.containsEntry("status", "PENDING").containsEntry("display_order", null);
		verify(storage, never()).promote(anyString(), anyString(), anyString());
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForMap("SELECT status, display_order FROM fishingstory.catch_report_images WHERE image_id=?", imageId))
				.containsEntry("status", "READY").containsEntry("display_order", 1);
	}

	@Test void cleanupRetainsDeletedReservationsUntilUploadExpiryThenDeletesEveryObject() throws Exception {
		long ready = reserveAndComplete();
		long pending = reserve();
		mvc.perform(delete("/api/catch-reports/me/101").header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		clearInvocations(storage);
		cleanup.run();
		verifyNoInteractions(storage);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images WHERE status='DELETE_PENDING'", Integer.class))
				.isEqualTo(2);
		expire(ready);
		expire(pending);
		cleanup.run();
		for (long imageId : new long[] {ready, pending}) {
			verify(storage).delete(CatchReportImageService.pendingKey(imageId, "master"));
			verify(storage).delete(CatchReportImageService.pendingKey(imageId, "thumbnail"));
			verify(storage).delete("catch-report-images/" + imageId + "/master.jpg");
			verify(storage).delete("catch-report-images/" + imageId + "/thumbnail.jpg");
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images", Integer.class)).isZero();
		clearInvocations(storage);
		cleanup.run();
		verifyNoInteractions(storage);
	}

	@Test void cleanupRetriesPartialDeletionAndPreservesReadyFinalObjects() throws Exception {
		long ready = reserveAndComplete();
		long pending = reserve();
		expire(ready);
		expire(pending);
		clearInvocations(storage);
		doThrow(new IllegalStateException("storage unavailable")).doNothing()
				.when(storage).delete("catch-report-images/" + pending + "/thumbnail.jpg");
		cleanup.run();
		assertThat(jdbc.queryForObject("SELECT temporary_cleaned FROM fishingstory.catch_report_images WHERE image_id=?", Boolean.class, ready))
				.isTrue();
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, pending))
				.isEqualTo("PENDING");
		verify(storage, never()).delete("catch-report-images/" + ready + "/master.jpg");
		verify(storage, never()).delete("catch-report-images/" + ready + "/thumbnail.jpg");
		cleanup.run();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images WHERE image_id=?", Integer.class, pending))
				.isZero();
		verify(storage, times(2)).delete("catch-report-images/" + pending + "/thumbnail.jpg");
		verify(storage, times(1)).delete(CatchReportImageService.pendingKey(ready, "master"));
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, ready))
				.isEqualTo("READY");
	}

	@Test void expiredOrDeletedPendingImagesCannotBeCompleted() throws Exception {
		long expired = reserve();
		expire(expired);
		long removed = reserveAndComplete();
		imageService.remove(USER_ID, 101, removed, false);
		clearInvocations(storage);
		for (long imageId : new long[] {expired, removed}) {
			mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
					.header("Authorization", "Bearer " + access))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		verifyNoInteractions(storage);
	}

	@Test void concurrentCompletionCopiesOnceAndKeepsOneDisplaySlot() throws Exception {
		long imageId = reserve();
		clearInvocations(storage);
		var start = new CyclicBarrier(2);
		try (var workers = Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<Void> complete = () -> {
				start.await(10, TimeUnit.SECONDS);
				imageService.complete(USER_ID, 101, imageId);
				return null;
			};
			var first = workers.submit(complete);
			var second = workers.submit(complete);
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		verify(storage, times(2)).inspect(anyString(), anyLong(), anyInt());
		verify(storage, times(2)).promote(anyString(), anyString(), anyString());
		assertThat(jdbc.queryForMap("SELECT status, display_order FROM fishingstory.catch_report_images WHERE image_id=?", imageId))
				.containsEntry("status", "READY").containsEntry("display_order", 1);
	}

	@Test void concurrentReservationsCannotExceedTenSlots() throws Exception {
		for (int index = 0; index < 9; index++) reserve();
		var start = new CyclicBarrier(2);
		try (var workers = Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<Boolean> reserve = () -> {
				start.await(10, TimeUnit.SECONDS);
				try {
					imageService.reserve(USER_ID, 101);
					return true;
				} catch (ApiException rejected) {
					if (rejected.errorCode() != ErrorCode.VALIDATION_ERROR) throw rejected;
					return false;
				}
			};
			var first = workers.submit(reserve);
			var second = workers.submit(reserve);
			assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images WHERE report_id=101", Integer.class))
				.isEqualTo(10);
	}

	@Test void cancellingPendingUploadReleasesSlotWithoutDeletingCleanupRecord() throws Exception {
		long ready = reserveAndComplete();
		long pending = reserve();
		for (int index = 0; index < 8; index++) reserve();
		mvc.perform(post("/api/catch-reports/me/101/images").header("Authorization", "Bearer " + access))
				.andExpect(status().isBadRequest());
		clearInvocations(storage);
		mvc.perform(delete("/api/catch-reports/me/102/images/{imageId}", pending)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNotFound());
		mvc.perform(delete("/api/catch-reports/me/101/images/{imageId}", pending)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		verifyNoInteractions(storage);
		assertThat(jdbc.queryForObject("SELECT status FROM fishingstory.catch_report_images WHERE image_id=?", String.class, pending))
				.isEqualTo("DELETE_PENDING");
		assertThat(jdbc.queryForObject("SELECT display_order FROM fishingstory.catch_report_images WHERE image_id=?", Integer.class, ready))
				.isEqualTo(1);
		reserve();
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", pending)
				.header("Authorization", "Bearer " + access)).andExpect(status().isBadRequest());
		mvc.perform(delete("/api/catch-reports/me/101/images/{imageId}", pending)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNotFound());
		expire(pending);
		cleanup.run();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images WHERE image_id=?", Integer.class, pending))
				.isZero();
	}

	private void expire(long imageId) {
		jdbc.update("UPDATE fishingstory.catch_report_images SET expires_at=? WHERE image_id=?",
				java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), imageId);
	}

	private long reserve() throws Exception {
		String body = mvc.perform(post("/api/catch-reports/me/101/images")
				.header("Authorization", "Bearer " + access)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("imageId").asLong();
	}

	private long reserveAndComplete() throws Exception {
		long imageId = reserve();
		mvc.perform(post("/api/catch-reports/me/101/images/{imageId}/complete", imageId)
				.header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
		return imageId;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
