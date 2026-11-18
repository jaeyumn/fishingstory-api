package kr.fishingstory.terms.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.common.id.SnowflakeIdGenerator;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(DevTermsSeederTests.Database.class)
class DevTermsSeederTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired DevTermsSeeder seeder;
	@BeforeEach
	void clear() {
		jdbc.update("DELETE FROM fishingstory.user_terms_agreement_history");
		jdbc.update("DELETE FROM fishingstory.terms");
	}
	@Test
	void seedsOnlyMissingCurrentPoliciesAndRerunPreservesPublishedRows() {
		seeder.run(null);
		var first = jdbc.queryForList("SELECT * FROM fishingstory.terms ORDER BY policy_type");
		assertThat(first).hasSize(2);
		assertThat(first).allSatisfy(row -> {
			assertThat(row.get("version")).isEqualTo("dev-placeholder-1");
			assertThat(row.get("content").toString()).contains("TODO", "임시");
			assertThat(row.get("published_at")).isEqualTo(row.get("effective_at"));
		});
		assertThat(first.get(0)).containsEntry("policy_type", "PRIVACY_POLICY").containsEntry("requires_consent", false);
		assertThat(first.get(1)).containsEntry("requires_consent", true).containsEntry("is_required", true);
		seeder.run(null);
		assertThat(jdbc.queryForList("SELECT * FROM fishingstory.terms ORDER BY policy_type")).isEqualTo(first);
	}
	@Test
	void existingRealCurrentTermsAreNeverOverwrittenOrReplaced() {
		jdbc.update("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, requires_consent, is_required, effective_at, published_at)
				VALUES (123, 'TERMS_OF_SERVICE', 'real', 'Real terms', 'Keep this content', true, true, now(), now())
				""");
		seeder.run(null);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.terms WHERE policy_type='TERMS_OF_SERVICE'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT content FROM fishingstory.terms WHERE terms_id=123", String.class)).isEqualTo("Keep this content");
	}
	@Test
	void productionProfileExcludesSeederEvenWhenDevIsAlsoEnabled() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("dev", "prod");
			context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
					"seed-test", java.util.Map.of("app.dev.seed-terms", "true")));
			context.register(DevTermsSeeder.class);
			context.refresh();
			assertThat(context.getBeansOfType(DevTermsSeeder.class)).isEmpty();
		}
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class Database {
		@Bean @ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
		}
		@Bean
		DevTermsSeeder testSeeder(SnowflakeIdGenerator ids, PlatformTransactionManager manager) { return new DevTermsSeeder(ids, manager); }
	}
}
