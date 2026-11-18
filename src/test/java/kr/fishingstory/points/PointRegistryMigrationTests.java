package kr.fishingstory.points;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointRegistryMigrationTests {
	@Test void v14AddsAnEmptyRegistryAndPreservesHistoricalMemberTermsReportsAndCollection() {
		try (var postgres = postgres()) {
			postgres.start();
			var data = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
			Flyway.configure().dataSource(data).defaultSchema("fishingstory").schemas("fishingstory").target("13").load().migrate();
			var jdbc = new JdbcTemplate(data);
			jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at) VALUES (501,'이전회원','이전회원',now())");
			jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id,user_id,provider,email,password_hash,email_verified_at) VALUES (502,501,'EMAIL','history@example.test','placeholder',now())");
			jdbc.update("INSERT INTO fishingstory.terms (terms_id,policy_type,version,title,content,requires_consent,is_required,effective_at,published_at) VALUES (503,'TERMS_OF_SERVICE','v1','이전 약관','본문',true,true,now(),now())");
			jdbc.update("INSERT INTO fishingstory.user_terms_agreement_history (agreement_history_id,user_id,terms_id,action) VALUES (504,501,503,'AGREED')");
			jdbc.update("INSERT INTO fishingstory.catch_reports (report_id,user_id,catch_status,water_type,fishing_date,memo,request_key) VALUES (505,501,'CAUGHT','SEA','2026-10-01','기존 조황','0123456789abcdef0123456789abcdef')");
			jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id,report_id,species_id,max_length_cm,max_weight_g,sort_order) VALUES (506,505,1003,30.5,100.0,0)");
			jdbc.update("INSERT INTO fishingstory.catch_report_species (report_species_id,report_id,custom_name,sort_order) VALUES (507,505,'옛 직접 입력',1)");
			jdbc.update("INSERT INTO fishingstory.user_fish_collection (collection_id,user_id,species_id,max_length_cm,max_weight_g,first_fishing_date,last_fishing_date) VALUES (508,501,1003,30.5,100.0,'2026-10-01','2026-10-01')");
			List<String> tables = List.of("users","user_auth","terms","user_terms_agreement_history","catch_reports","catch_report_species","user_fish_collection","fish_species","fish_species_alias");
			Map<String,List<Map<String,Object>>> before = new LinkedHashMap<>();
			for (String table : tables) before.put(table,jdbc.queryForList("SELECT * FROM fishingstory." + table + " ORDER BY 1"));
			var upgrade = Flyway.configure().dataSource(data).defaultSchema("fishingstory").schemas("fishingstory").target("14").load();
			upgrade.migrate();
			assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("14");
			for (String table : tables) assertThat(jdbc.queryForList("SELECT * FROM fishingstory." + table + " ORDER BY 1")).isEqualTo(before.get(table));
			for (String table : List.of("fishing_regions","fishing_zones","fishing_points","fishing_point_aliases")) {
				assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory." + table,Integer.class)).isZero();
			}

			jdbc.update("""
					INSERT INTO fishingstory.catch_report_images
					(image_id,report_id,user_id,master_object_key,thumbnail_object_key,status,display_order,created_at,expires_at,updated_at)
					VALUES (509,505,501,'old-master','old-thumbnail','READY',1,now(),now(),now())
					""");
			var current = Flyway.configure().dataSource(data).defaultSchema("fishingstory").schemas("fishingstory").load();
			current.migrate();
			assertThat(current.info().current().getVersion().getVersion()).isEqualTo("16");
			assertThat(jdbc.queryForObject("SELECT access_token_generation FROM fishingstory.users WHERE user_id=501", Long.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT request_key FROM fishingstory.catch_report_images WHERE image_id=509", String.class)).isNull();
			assertThat(jdbc.queryForObject("SELECT master_object_key FROM fishingstory.catch_report_images WHERE image_id=509 AND status='READY'", String.class)).isEqualTo("old-master");
			assertThat(jdbc.queryForList("SELECT * FROM fishingstory.catch_reports ORDER BY 1")).isEqualTo(before.get("catch_reports"));
		}
	}
	@Test void everyMigrationRollsBackMissingParentsAliasesAndMixedDemoReferences() {
		try (var postgres = postgres()) {
			postgres.start();
			var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
			for (String scenario : List.of("region","zone","alias","demo")) {
				String database = "point_" + scenario;
				admin.execute("CREATE DATABASE " + database);
				var data = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+database),postgres.getUsername(),postgres.getPassword());
				var flyway = Flyway.configure().dataSource(data).defaultSchema("fishingstory").schemas("fishingstory")
						.locations("classpath:db/migration","classpath:db/point-check/"+scenario).load();
				assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("Fishing point registry needs existing");
				assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");
				var jdbc = new JdbcTemplate(data);
				assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.flyway_schema_history WHERE version='100' AND success",Integer.class)).isZero();
				assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_regions",Integer.class)).isZero();
				assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_zones",Integer.class)).isZero();
				assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_points",Integer.class)).isZero();
				assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.fishing_point_aliases",Integer.class)).isZero();
			}
		}
	}
	private static PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
	}
}
