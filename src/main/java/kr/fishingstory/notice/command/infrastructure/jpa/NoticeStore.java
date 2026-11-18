package kr.fishingstory.notice.command.infrastructure.jpa;

import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import kr.fishingstory.notice.command.domain.Notice;

@Repository
public class NoticeStore {
	@PersistenceContext private EntityManager entityManager;
	public void insert(Notice notice) { entityManager.persist(notice); }
	public Optional<Notice> lock(long id) {
		return Optional.ofNullable(entityManager.find(Notice.class, id, LockModeType.PESSIMISTIC_WRITE));
	}
}
