package kr.fishingstory.inquiry.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
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
@Import(InquiryApiTests.DatabaseConfiguration.class)
class InquiryApiTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired AccessTokenIssuer tokens;
	String user;
	String other;
	String admin;
	@BeforeEach void prepare() {
		jdbc.update("DELETE FROM fishingstory.inquiry_messages");
		jdbc.update("DELETE FROM fishingstory.inquiries");
		jdbc.update("DELETE FROM fishingstory.users");
		jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at,role) VALUES (101,'문의자','문의자',now(),'USER'),(102,'타인','타인',now(),'USER'),(103,'운영자','운영자',now(),'ADMIN')");
		user = token(101, "USER"); other = token(102, "USER"); admin = token(103, "ADMIN");
	}
	@Test void ownerAndAdminCanReplyCloseAndReopen() throws Exception {
		long id = create("a".repeat(32));
		for (String base : java.util.List.of("/api/inquiries/", "/api/admin/inquiries/")) {
			String auth = base.contains("admin") ? admin : user;
			mvc.perform(patch(base + id + "/status").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
					.andExpect(status().isNoContent());
			mvc.perform(post(base + id + "/messages").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(message(UUID.randomUUID().toString().replace("-", ""), "추가")))
					.andExpect(status().isConflict());
			mvc.perform(patch(base + id + "/status").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
					.andExpect(status().isNoContent());
			mvc.perform(post(base + id + "/messages").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(message("b".repeat(32), "추가")))
					.andExpect(status().isCreated());
		}
		mvc.perform(get("/api/inquiries/" + id).header("Authorization", user).param("limit", "2"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.messages.length()").value(2)).andExpect(jsonPath("$.messages[0].senderRole").value("ADMIN"))
				.andExpect(jsonPath("$.messages[0].senderId").doesNotExist()).andExpect(jsonPath("$.nextBefore").isNumber());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.inquiry_messages", Integer.class)).isEqualTo(3);
	}
	@Test void hidesOtherMembersConversationsAndRejectsForgedAdminClaims() throws Exception {
		long id = create("a".repeat(32));
		mvc.perform(get("/api/inquiries").header("Authorization", other).param("admin", "true"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/inquiries/" + id).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post("/api/inquiries/" + id + "/messages").header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content(message("b".repeat(32), "침입")))
				.andExpect(status().isNotFound());
		mvc.perform(patch("/api/inquiries/" + id + "/status").header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
				.andExpect(status().isNotFound());
		for (String auth : java.util.List.of(other, token(102, "ADMIN"), token(103, "USER"))) {
			mvc.perform(get("/api/admin/inquiries").header("Authorization", auth)).andExpect(status().isForbidden());
			mvc.perform(post("/api/admin/inquiries/" + id + "/messages").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(message("b".repeat(32), "침입")))
					.andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isUnauthorized());
		jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING', withdrawal_requested_at=now() WHERE user_id=101");
		mvc.perform(get("/api/inquiries/" + id).header("Authorization", user)).andExpect(status().isForbidden());
	}
	@Test void retriesCreationAndConcurrentRepliesWithoutDuplicates() throws Exception {
		String key = "a".repeat(32);
		long id = create(key);
		assertThat(create(key)).isEqualTo(id);
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			var jobs = java.util.stream.IntStream.range(0, 4).mapToObj(index -> pool.submit(() ->
				mvc.perform(post("/api/inquiries/" + id + "/messages").header("Authorization", user)
					.contentType(MediaType.APPLICATION_JSON).content(message("c".repeat(32), "중복 없는 메시지")))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())).toList();
			var responses = new java.util.HashSet<String>();
			for (var job : jobs) responses.add(job.get(15, java.util.concurrent.TimeUnit.SECONDS));
			assertThat(responses).hasSize(1);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.inquiries", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.inquiry_messages", Integer.class)).isEqualTo(2);
		mvc.perform(post("/api/inquiries/" + id + "/messages").header("Authorization", user)
				.contentType(MediaType.APPLICATION_JSON).content(message("c".repeat(32), "변경된 메시지"))).andExpect(status().isConflict());
	}
	@Test void pagesMessagesAndFiltersClosedInquiriesWithoutExposingOtherUsers() throws Exception {
		long id = create("a".repeat(32));
		for (int i = 0; i < 3; i++) mvc.perform(post("/api/inquiries/" + id + "/messages").header("Authorization", user)
				.contentType(MediaType.APPLICATION_JSON).content(message(UUID.randomUUID().toString().replace("-", ""), "메시지" + i))).andExpect(status().isCreated());
		var first = mvc.perform(get("/api/inquiries/" + id).header("Authorization", user).param("limit", "2"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long before = json.readTree(first).get("nextBefore").asLong();
		mvc.perform(get("/api/inquiries/" + id).header("Authorization", user).param("before", String.valueOf(before)).param("limit", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2)).andExpect(jsonPath("$.nextBefore").isEmpty());
		mvc.perform(get("/api/inquiries").header("Authorization", user).param("status", "CLOSED"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}
	@Test void validatesInputAndPaginationWithoutWriting() throws Exception {
		for (String content : java.util.List.of(" ", "가".repeat(2001))) mvc.perform(post("/api/inquiries").header("Authorization", user)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "제목", "content", content, "requestKey", "a".repeat(32)))))
				.andExpect(status().isBadRequest());
		for (var pair : java.util.List.of(Map.of("page", "-1"), Map.of("limit", "51"), Map.of("status", "INVALID"))) {
			var request = get("/api/inquiries").header("Authorization", user); pair.forEach(request::param);
			mvc.perform(request).andExpect(status().isBadRequest());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.inquiries", Integer.class)).isZero();
	}
	private long create(String key) throws Exception {
		var response = mvc.perform(post("/api/inquiries").header("Authorization", user).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("title", "문의 제목", "content", "첫 메시지", "requestKey", key))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("inquiryId").asLong();
	}
	private String message(String key, String content) throws Exception { return json.writeValueAsString(Map.of("requestKey", key, "content", content)); }
	private String token(long id, String role) { return "Bearer " + tokens.issue(id, role, UUID.randomUUID().toString()).value(); }
	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean @ServiceConnection PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
	}
}
