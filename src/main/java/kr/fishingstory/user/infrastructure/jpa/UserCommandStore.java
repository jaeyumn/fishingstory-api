package kr.fishingstory.user.infrastructure.jpa;

import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import kr.fishingstory.user.domain.User;

@Repository
public class UserCommandStore {
	@PersistenceContext private EntityManager entityManager;

	public Optional<User> lock(long userId) {
		return Optional.ofNullable(entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE));
	}

	public void flush() { entityManager.flush(); }
}
