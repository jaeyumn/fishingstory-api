package kr.fishingstory.catchreport.command.infrastructure.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import kr.fishingstory.catchreport.command.domain.CatchReport;
import kr.fishingstory.catchreport.command.domain.CatchReportSpecies;
import kr.fishingstory.catchreport.command.domain.FishSpecies;
import kr.fishingstory.catchreport.command.domain.UserFishCollection;

@Repository
public class CatchReportCommandStore {
	@PersistenceContext private EntityManager entityManager;

	public void insert(CatchReport report) {
		entityManager.persist(report);
	}

	public Optional<CatchReport> findMine(long userId, long reportId) {
		return entityManager.createQuery("""
				SELECT r FROM CatchReport r WHERE r.id = :reportId AND r.userId = :userId
				""", CatchReport.class)
				.setParameter("reportId", reportId).setParameter("userId", userId)
				.getResultStream().findFirst();
	}

	public Optional<CatchReport> findRequested(long userId, String requestKey) {
		return entityManager.createQuery("""
				SELECT r FROM CatchReport r WHERE r.userId = :userId AND r.requestKey = :requestKey
				""", CatchReport.class)
				.setParameter("userId", userId).setParameter("requestKey", requestKey)
				.getResultStream().findFirst();
	}

	public List<CatchReportSpecies> findReportSpecies(long reportId) {
		return entityManager.createQuery("""
				SELECT s FROM CatchReportSpecies s WHERE s.reportId = :reportId
				""", CatchReportSpecies.class).setParameter("reportId", reportId).getResultList();
	}

	public List<FishSpecies> findSpecies(Collection<Long> speciesIds) {
		if (speciesIds.isEmpty()) return List.of();
		return entityManager.createQuery("""
				SELECT s FROM FishSpecies s WHERE s.id IN :ids
				""", FishSpecies.class).setParameter("ids", speciesIds).getResultList();
	}

	public void delete(CatchReportSpecies species) { entityManager.remove(species); }
	public void delete(CatchReport report) { entityManager.remove(report); }
	public void delete(UserFishCollection collection) { entityManager.remove(collection); }

	public Object[] collectionSummary(long userId, long speciesId) {
		return (Object[]) entityManager.createQuery("""
				SELECT COUNT(s.id), MIN(r.fishingDate), MAX(r.fishingDate), MAX(s.maxLengthCm), MAX(s.maxWeightG)
				FROM CatchReportSpecies s, CatchReport r
				WHERE s.reportId = r.id AND r.userId = :userId AND s.speciesId = :speciesId
				""").setParameter("userId", userId).setParameter("speciesId", speciesId)
				.getSingleResult();
	}

	public List<FishSpecies> findActiveSpecies(Collection<Long> speciesIds) {
		if (speciesIds.isEmpty()) return List.of();
		return entityManager.createQuery("""
				SELECT s FROM FishSpecies s
				WHERE s.id IN :ids AND s.active = true
				""", FishSpecies.class).setParameter("ids", speciesIds).getResultList();
	}

	public void insert(CatchReportSpecies species) { entityManager.persist(species); }

	public Optional<UserFishCollection> lockCollection(long userId, long speciesId) {
		return entityManager.createQuery("""
				SELECT c FROM UserFishCollection c
				WHERE c.userId = :userId AND c.speciesId = :speciesId
				""", UserFishCollection.class)
				.setParameter("userId", userId).setParameter("speciesId", speciesId)
				.setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
	}

	public void insert(UserFishCollection collection) { entityManager.persist(collection); }
	public void flush() { entityManager.flush(); }
}
