package kr.fishingstory.catchreport.command.infrastructure.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;
import kr.fishingstory.catchreport.command.domain.CatchReportImage;

@Repository
public class CatchReportImageStore {
	@PersistenceContext private EntityManager entityManager;

	public void insert(CatchReportImage image) { entityManager.persist(image); }
	public void delete(CatchReportImage image) { entityManager.remove(image); }
	public void flush() { entityManager.flush(); }

	public Optional<CatchReportImage> find(long userId, long reportId, long imageId) {
		return entityManager.createQuery("""
				SELECT i FROM CatchReportImage i
				WHERE i.id = :imageId AND i.reportId = :reportId AND i.userId = :userId
				""", CatchReportImage.class)
				.setParameter("imageId", imageId).setParameter("reportId", reportId)
				.setParameter("userId", userId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
				.getResultStream().findFirst();
	}

	public Optional<CatchReportImage> findRequested(long userId, long reportId, String requestKey) {
		return entityManager.createQuery("""
				SELECT i FROM CatchReportImage i
				WHERE i.userId = :userId AND i.reportId = :reportId AND i.requestKey = :requestKey
				""", CatchReportImage.class).setParameter("userId", userId).setParameter("reportId", reportId)
				.setParameter("requestKey", requestKey).setLockMode(LockModeType.PESSIMISTIC_WRITE)
				.getResultStream().findFirst();
	}

	public List<CatchReportImage> list(long reportId) {
		return entityManager.createQuery("""
				SELECT i FROM CatchReportImage i
				WHERE i.reportId = :reportId AND i.status = 'READY'
				ORDER BY i.displayOrder, i.id
				""", CatchReportImage.class).setParameter("reportId", reportId).getResultList();
	}

	public long activeCount(long reportId, Instant now) {
		return entityManager.createQuery("""
				SELECT COUNT(i.id) FROM CatchReportImage i
				WHERE i.reportId = :reportId
				AND (i.status = 'READY' OR (i.status = 'PENDING' AND i.expiresAt > :now))
				""", Long.class).setParameter("reportId", reportId).setParameter("now", now)
				.getSingleResult();
	}

	public List<CatchReportImage> allForReport(long reportId) {
		return entityManager.createQuery("""
				SELECT i FROM CatchReportImage i WHERE i.reportId = :reportId
				AND i.status <> 'DELETE_PENDING'
				""", CatchReportImage.class).setParameter("reportId", reportId).getResultList();
	}

	public List<CatchReportImage> cleanupCandidates(Instant now) {
		return entityManager.createQuery("""
				SELECT i FROM CatchReportImage i
				WHERE (i.status IN ('PENDING', 'DELETE_PENDING') AND i.expiresAt <= :now)
				OR (i.status = 'READY' AND i.temporaryCleaned = false AND i.expiresAt <= :now)
				ORDER BY i.createdAt
				""", CatchReportImage.class).setParameter("now", now).setMaxResults(100).getResultList();
	}

	public Optional<CatchReportImage> findById(long imageId) {
		return Optional.ofNullable(entityManager.find(CatchReportImage.class, imageId, LockModeType.PESSIMISTIC_WRITE));
	}
}
