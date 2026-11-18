package kr.fishingstory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FishingstoryApiApplicationTests.DatabaseConfiguration.class)
class CurrentTermsApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clearTerms() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.terms");
	}

	@Test
	void returnsEmptyListWithoutAuthenticationWhenNoPolicyExists() throws Exception {
		mvc.perform(get("/api/terms/current"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().json("{\"items\":[]}"));
	}

	@Test
	void selectsLatestEffectiveVersionPerTypeAndPreservesMarkdown() throws Exception {
		insertPolicy(1, "TERMS_OF_SERVICE", "9.0", -30, -40);
		insertPolicy(2, "TERMS_OF_SERVICE", "10.0", -10, -20);
		insertPolicy(3, "PRIVACY_POLICY", "1.0", -10, -20);
		insertPolicy(4, "TERMS_OF_SERVICE", "11.0", 10, -1);
		insertPolicy(5, "TERMS_OF_SERVICE", "12.0", 20, 10);
		insertPolicy(6, "TERMS_OF_SERVICE", "13.0", -1, -2);
		jdbc.update("UPDATE fishingstory.terms SET published_at = NULL WHERE terms_id = 6");
		mvc.perform(get("/api/terms/current"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].termsId").value(3))
				.andExpect(jsonPath("$.items[0].policyType").value("PRIVACY_POLICY"))
				.andExpect(jsonPath("$.items[0].requiresConsent").value(false))
				.andExpect(jsonPath("$.items[0].isRequired").value(false))
				.andExpect(jsonPath("$.items[1].termsId").value(2))
				.andExpect(jsonPath("$.items[1].version").value("10.0"))
				.andExpect(jsonPath("$.items[1].title").value("이용 안내"))
				.andExpect(jsonPath("$.items[1].content").value("# 약관\n\n- **필수** 안내"))
				.andExpect(jsonPath("$.items[1].requiresConsent").value(true))
				.andExpect(jsonPath("$.items[1].isRequired").value(true))
				.andExpect(jsonPath("$.items[1].effectiveAt").isString())
				.andExpect(jsonPath("$.items[1].publishedAt").isString());
	}

	@Test
	void excludesDraftsAndFuturePoliciesWhenNoCurrentVersionExists() throws Exception {
		insertPolicy(1, "TERMS_OF_SERVICE", "1.0", 10, -1);
		insertPolicy(2, "PRIVACY_POLICY", "1.0", -1, -2);
		jdbc.update("UPDATE fishingstory.terms SET published_at = NULL WHERE terms_id = 2");
		mvc.perform(get("/api/terms/current"))
				.andExpect(status().isOk()).andExpect(content().json("{\"items\":[]}"));
	}

	@Test
	void exposesOpenApiContract() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/terms/current'].get").exists());
	}

	@Test
	void nextRequestSeesNewlyPublishedEffectiveVersion() throws Exception {
		insertPolicy(1, "TERMS_OF_SERVICE", "1.0", -10, -20);
		mvc.perform(get("/api/terms/current"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].termsId").value(1));
		insertPolicy(2, "TERMS_OF_SERVICE", "2.0", 0, 0);
		mvc.perform(get("/api/terms/current"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].termsId").value(2));
	}

	private void insertPolicy(long id, String type, String version, int effectiveDays, int publishedDays) {
		jdbc.update("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, requires_consent, is_required, effective_at, published_at)
				VALUES (?, ?, ?, ?, ?, ?, ?,
				    statement_timestamp() + ? * INTERVAL '1 day', statement_timestamp() + ? * INTERVAL '1 day')
				""", id, type, version, "이용 안내", "# 약관\n\n- **필수** 안내",
				type.equals("TERMS_OF_SERVICE"), type.equals("TERMS_OF_SERVICE"), effectiveDays, publishedDays);
	}
}
