package kr.fishingstory.notice.api;

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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(NoticeApiTests.DatabaseConfiguration.class)
class NoticeApiTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired AccessTokenIssuer tokens;
	String admin;
	String user;
	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.notices");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("INSERT INTO fishingstory.users (user_id, nickname, nickname_key, age_confirmed_at, role) VALUES (101, '관리자', '관리자', now(), 'ADMIN'), (102, '회원', '회원', now(), 'USER')");
		admin = token(101, "ADMIN");
		user = token(102, "USER");
	}
	@Test void createsDraftPublishesPinsAndHidesWithoutLeakingDrafts() throws Exception {
		long draft = create("비공개 초안", false, false);
		long pinned = create("고정 공지", true, true);
		long latest = create("최신 공지", true, false);
		mvc.perform(get("/api/notices").header("Authorization", user).param("limit", "1"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].noticeId").value(pinned)).andExpect(jsonPath("$.hasNext").value(true));
		mvc.perform(get("/api/notices").header("Authorization", user).param("limit", "1").param("page", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].noticeId").value(latest))
				.andExpect(jsonPath("$.hasNext").value(false));
		mvc.perform(get("/api/notices/" + draft).header("Authorization", user)).andExpect(status().isNotFound());
		mvc.perform(get("/api/admin/notices/" + draft).header("Authorization", admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content").value("# 본문\n내용"));
		mvc.perform(get("/api/admin/notices").header("Authorization", admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
		mvc.perform(put("/api/admin/notices/" + draft).header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(body("공개 전환", true, false)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/notices/" + draft).header("Authorization", user))
				.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("공개 전환"));
		mvc.perform(put("/api/admin/notices/" + draft).header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(body("다시 비공개", false, false)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/notices/" + draft).header("Authorization", user)).andExpect(status().isNotFound());
	}
	@Test void requiresBothAdminTokenAndCurrentActiveAdminRow() throws Exception {
		long id = create("관리 대상", false, false);
		for (String credential : java.util.List.of(user, token(102, "ADMIN"), token(101, "USER"))) {
			mvc.perform(post("/api/admin/notices").header("Authorization", credential)
					.contentType(MediaType.APPLICATION_JSON).content(body("금지", true, true))).andExpect(status().isForbidden());
			mvc.perform(get("/api/admin/notices").header("Authorization", credential)).andExpect(status().isForbidden());
			mvc.perform(get("/api/admin/notices/" + id).header("Authorization", credential)).andExpect(status().isForbidden());
			mvc.perform(put("/api/admin/notices/" + id).header("Authorization", credential)
					.contentType(MediaType.APPLICATION_JSON).content(body("금지", true, true))).andExpect(status().isForbidden());
		}
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=101");
		mvc.perform(get("/api/admin/notices").header("Authorization", admin)).andExpect(status().isForbidden());
		mvc.perform(put("/api/admin/notices/" + id).header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(body("금지", true, true))).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT title FROM fishingstory.notices WHERE notice_id=?", String.class, id)).isEqualTo("관리 대상");
	}
	@Test void rejectsAnonymousAndInactiveReadersAndInvalidPagination() throws Exception {
		mvc.perform(get("/api/notices")).andExpect(status().isUnauthorized());
		for (var params : java.util.List.of(Map.of("page", "-1"), Map.of("limit", "0"), Map.of("limit", "51"))) {
			var request = get("/api/notices").header("Authorization", user);
			params.forEach(request::param);
			mvc.perform(request).andExpect(status().isBadRequest());
		}
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=102");
		mvc.perform(get("/api/notices").header("Authorization", user)).andExpect(status().isForbidden());
	}
	@Test void validatesContentAndMissingFlagsWithoutSaving() throws Exception {
		for (var request : java.util.List.of(
				Map.of("title", " ", "content", "본문", "published", true, "pinned", false),
				Map.of("title", "가".repeat(101), "content", "본문", "published", true, "pinned", false),
				Map.of("title", "공지", "content", "가".repeat(20001), "published", true, "pinned", false),
				Map.of("title", "공지", "content", " ", "published", true, "pinned", false),
				Map.of("title", "공지", "content", "본문"))) {
			mvc.perform(post("/api/admin/notices").header("Authorization", admin)
					.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.notices", Integer.class)).isZero();
	}
	@Test void rejectsNulContentOnCreateAndUpdateWithoutChangingPublication() throws Exception {
		long id = create("기존 공지", false, false);
		var before = jdbc.queryForList("SELECT * FROM fishingstory.notices");
		for (String content : java.util.List.of("\0본문", "본\0문", "본문\0")) {
			String body = json.writeValueAsString(Map.of("title", "변경 공지", "content", content,
					"published", true, "pinned", true));
			mvc.perform(post("/api/admin/notices").header("Authorization", admin)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			mvc.perform(put("/api/admin/notices/" + id).header("Authorization", admin)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.notices")).isEqualTo(before);
		mvc.perform(get("/api/notices/" + id).header("Authorization", user)).andExpect(status().isNotFound());
	}

	private long create(String title, boolean published, boolean pinned) throws Exception {
		var response = mvc.perform(post("/api/admin/notices").header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(body(title, published, pinned)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("noticeId").asLong();
	}
	private String body(String title, boolean published, boolean pinned) throws Exception {
		return json.writeValueAsString(Map.of("title", title, "content", "# 본문\n내용", "published", published, "pinned", pinned));
	}
	private String token(long id, String role) { return "Bearer " + tokens.issue(id, role, UUID.randomUUID().toString()).value(); }
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
