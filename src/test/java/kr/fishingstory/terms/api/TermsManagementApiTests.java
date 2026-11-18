package kr.fishingstory.terms.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TermsManagementApiTests.DatabaseConfiguration.class)
class TermsManagementApiTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired AccessTokenIssuer tokens;
	String admin;
	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.terms");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at,role) VALUES (101,'운영자','운영자',now(),'ADMIN'),(102,'회원','회원',now(),'USER')");
		admin = token(101, "ADMIN");
	}
	@Test void publishesImmutableVersionAndKeepsFuturePolicyOutOfCurrentTerms() throws Exception {
		long id = create("1.0", Instant.now().plusSeconds(86400));
		mvc.perform(get("/api/terms/current")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/terms/upcoming")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		publish(id, false);
		mvc.perform(get("/api/terms/current")).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/terms/upcoming")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].termsId").value(id)).andExpect(jsonPath("$.items[0].content").value("# 약관\n내용"));
		var before = jdbc.queryForMap("SELECT * FROM fishingstory.terms WHERE terms_id=?", id);
		publish(id, true);
		mvc.perform(put("/api/admin/terms/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content(body("2.0", Instant.now().plusSeconds(200000)))).andExpect(status().isConflict());
		mvc.perform(delete("/api/admin/terms/" + id).header("Authorization", admin)).andExpect(status().isMethodNotAllowed());
		assertThat(jdbc.queryForMap("SELECT * FROM fishingstory.terms WHERE terms_id=?", id)).isEqualTo(before);
	}
	@Test void editsDraftAndPublishesImmediatelyWithoutDeletingHistory() throws Exception {
		long id = create("1.0", Instant.now().plusSeconds(86400));
		mvc.perform(put("/api/admin/terms/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content(body("1.1", Instant.now().plusSeconds(86400)))).andExpect(status().isNoContent());
		publish(id, true);
		mvc.perform(get("/api/terms/current")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].termsId").value(id)).andExpect(jsonPath("$.items[0].version").value("1.1"));
		mvc.perform(get("/api/terms/upcoming")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		long next = create("2.0", Instant.now().plusSeconds(86401));
		publish(next, false);
		mvc.perform(get("/api/terms/current")).andExpect(jsonPath("$.items[0].termsId").value(id));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.terms", Integer.class)).isEqualTo(2);
	}
	@Test void enforcesActiveAdminAndDoesNotExposeDraftsToUsers() throws Exception {
		long id = create("1.0", Instant.now().plusSeconds(86400));
		for (String credential : java.util.List.of(token(102, "USER"), token(102, "ADMIN"), token(101, "USER"))) {
			mvc.perform(get("/api/admin/terms/" + id).header("Authorization", credential)).andExpect(status().isForbidden());
			mvc.perform(post("/api/admin/terms/" + id + "/publish").header("Authorization", credential)
					.contentType(MediaType.APPLICATION_JSON).content(publication(id, true))).andExpect(status().isForbidden());
		}
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=101");
		mvc.perform(get("/api/admin/terms").header("Authorization", admin)).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/terms")).andExpect(status().isUnauthorized());
	}
	@Test void rejectsDuplicateVersionsPastPublicationAndOversizedVersion() throws Exception {
		Instant effective = Instant.now().plusSeconds(86400);
		create("1.0", effective);
		mvc.perform(post("/api/admin/terms").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content(body("1.0", effective.plusSeconds(1)))).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/terms").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content(body("x".repeat(31), effective.plusSeconds(2)))).andExpect(status().isBadRequest());
		long past = create("old", Instant.now().minusSeconds(100));
		mvc.perform(post("/api/admin/terms/" + past + "/publish").header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(publication(past, false))).andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT published_at FROM fishingstory.terms WHERE terms_id=?", Object.class, past)).isNull();
	}
	@Test void refusesPublicationWhenTheReviewedDraftHasChanged() throws Exception {
		long id = create("1.0", Instant.now().plusSeconds(86400));
		String stale = publication(id, true);
		mvc.perform(put("/api/admin/terms/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content(body("1.1", Instant.now().plusSeconds(86401)))).andExpect(status().isNoContent());
		mvc.perform(post("/api/admin/terms/" + id + "/publish").header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(stale)).andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT published_at FROM fishingstory.terms WHERE terms_id=?", Object.class, id)).isNull();
	}
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void publicationIsImmediatelyVisibleOnTheCorrectEndpointUsingDatabaseTimestamps(boolean immediate) throws Exception {
		Instant before = databaseTime();
		Instant effectiveAt = before.plusSeconds(86400);
		long id = create("clock", effectiveAt);
		Instant created = jdbc.queryForObject("SELECT created_at FROM fishingstory.terms WHERE terms_id=?", OffsetDateTime.class, id).toInstant();
		assertThat(created).isBetween(before, databaseTime());

		publish(id, immediate);

		Instant after = databaseTime();
		Instant published = jdbc.queryForObject("SELECT published_at FROM fishingstory.terms WHERE terms_id=?", OffsetDateTime.class, id).toInstant();
		Instant effective = jdbc.queryForObject("SELECT effective_at FROM fishingstory.terms WHERE terms_id=?", OffsetDateTime.class, id).toInstant();
		Instant updated = jdbc.queryForObject("SELECT updated_at FROM fishingstory.terms WHERE terms_id=?", OffsetDateTime.class, id).toInstant();
		assertThat(published).isBetween(before, after);
		assertThat(updated).isEqualTo(published);
		assertThat(effective).isEqualTo(immediate ? published : effectiveAt);
		mvc.perform(get(immediate ? "/api/terms/current" : "/api/terms/upcoming"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].termsId").value(id))
				.andExpect(jsonPath("$.items[0].content").value("# 약관\n내용"));
		mvc.perform(get(immediate ? "/api/terms/upcoming" : "/api/terms/current"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}
	private Instant databaseTime() {
		return jdbc.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant();
	}
	private long create(String version, Instant time) throws Exception {
		var response = mvc.perform(post("/api/admin/terms").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content(body(version, time))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("termsId").asLong();
	}
	private String body(String version, Instant time) throws Exception {
		return json.writeValueAsString(Map.of("policyType", "TERMS_OF_SERVICE", "version", version,
				"title", "서비스 약관", "content", "# 약관\n내용", "effectiveAt", time.toString()));
	}
	private void publish(long id, boolean immediate) throws Exception {
		mvc.perform(post("/api/admin/terms/" + id + "/publish").header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(publication(id, immediate)))
				.andExpect(status().isNoContent());
	}
	private String publication(long id, boolean immediate) throws Exception {
		String updated = jdbc.queryForObject("SELECT updated_at FROM fishingstory.terms WHERE terms_id=?", java.sql.Timestamp.class, id).toInstant().toString();
		return json.writeValueAsString(Map.of("immediately", immediate, "expectedUpdatedAt", updated));
	}
	private String token(long id, String role) { return "Bearer " + tokens.issue(id, role, UUID.randomUUID().toString()).value(); }
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
