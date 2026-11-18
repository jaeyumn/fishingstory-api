package kr.fishingstory.points.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.common.id.SnowflakeIdGenerator;

/** Optional dev data only. All coordinates and names are illustrative, with no provider mappings. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.dev.seed-points", havingValue = "true")
public class DevPointsSeeder implements ApplicationRunner {
	@PersistenceContext private EntityManager entityManager;
	private final SnowflakeIdGenerator ids;
	private final TransactionTemplate transaction;
	public DevPointsSeeder(SnowflakeIdGenerator ids, PlatformTransactionManager manager) {
		this.ids = ids; this.transaction = new TransactionTemplate(manager);
	}
	@Override public void run(ApplicationArguments args) {
		transaction.executeWithoutResult(status -> {
			// Serialize simultaneous dev starts so repeated boots cannot duplicate demo point names.
			entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(7140031)").getSingleResult();
			long busan = region("[더미] 부산", 0);
			long gyeonggi = region("[더미] 경기도", 1);
			long yeongdo = zone(busan, "[더미] 영도");
			long lake = zone(gyeonggi, "[더미] 가상호수");
			point(yeongdo, "[더미] 가상 해변 A", "SEA", 129.08, 35.06, "PUBLISHED", "더미해변");
			point(yeongdo, "[더미] 가상 방파제 B", "SEA", 129.09, 35.07, "PUBLISHED", "더미방파제");
			point(lake, "[더미] 가상 호수 C", "FRESHWATER", 127.10, 37.40, "PUBLISHED", "더미호수");
			point(yeongdo, "[더미] 숨김 예시", "SEA", 129.08, 35.06, "HIDDEN", "더미숨김");
			point(yeongdo, "[더미] 작성 중 예시", "SEA", 129.08, 35.06, "DRAFT", "더미초안");
		});
	}
	private long region(String name, int order) {
		entityManager.createNativeQuery("""
				INSERT INTO fishingstory.fishing_regions (region_id,name,sort_order,is_demo)
				VALUES (:id,:name,:order,true) ON CONFLICT (name) DO NOTHING
				""").setParameter("id", ids.nextId()).setParameter("name", name).setParameter("order", order).executeUpdate();
		return id("SELECT region_id FROM fishingstory.fishing_regions WHERE name=:name AND is_demo", name, null);
	}
	private long zone(long regionId, String name) {
		entityManager.createNativeQuery("""
				INSERT INTO fishingstory.fishing_zones (zone_id,region_id,name,is_demo)
				VALUES (:id,:parent,:name,true) ON CONFLICT (region_id,name) DO NOTHING
				""").setParameter("id", ids.nextId()).setParameter("parent", regionId).setParameter("name", name).executeUpdate();
		return id("SELECT zone_id FROM fishingstory.fishing_zones WHERE name=:name AND region_id=:parent AND is_demo", name, regionId);
	}
	private void point(long zoneId, String name, String water, double longitude, double latitude, String publication, String alias) {
		entityManager.createNativeQuery("""
				INSERT INTO fishingstory.fishing_points
				(point_id,zone_id,name,water_type,longitude,latitude,description,publication_status,is_demo)
				SELECT :id,:parent,:name,:water,:longitude,:latitude,:description,:publication,true
				WHERE NOT EXISTS (SELECT 1 FROM fishingstory.fishing_points WHERE zone_id=:parent AND name=:name AND is_demo)
				""").setParameter("id", ids.nextId()).setParameter("parent", zoneId).setParameter("name", name)
				.setParameter("water", water).setParameter("longitude", longitude).setParameter("latitude", latitude)
				.setParameter("description", "개발용 가상 장소와 예시 좌표입니다. 실제 포인트·낚시 허용·안전·환경 자료를 의미하지 않습니다.")
				.setParameter("publication", publication).executeUpdate();
		long pointId = id("SELECT point_id FROM fishingstory.fishing_points WHERE zone_id=:parent AND name=:name AND is_demo", name, zoneId);
		entityManager.createNativeQuery("""
				INSERT INTO fishingstory.fishing_point_aliases (alias_id,point_id,alias)
				VALUES (:id,:parent,:alias) ON CONFLICT (point_id,alias) DO NOTHING
				""").setParameter("id", ids.nextId()).setParameter("parent", pointId).setParameter("alias", alias).executeUpdate();
	}
	private long id(String sql, String name, Long parent) {
		var query = entityManager.createNativeQuery(sql).setParameter("name", name);
		if (parent != null) query.setParameter("parent", parent);
		var values = query.getResultList();
		if (values.size() != 1) throw new IllegalStateException("Review conflicting demo point registry rows before enabling demo data");
		return ((Number) values.getFirst()).longValue();
	}
}
