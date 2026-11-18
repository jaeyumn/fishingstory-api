package kr.fishingstory.terms.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import kr.fishingstory.terms.domain.Terms;

@Repository
public class TermsCommandStore {
	@PersistenceContext private EntityManager entityManager;
	public void insert(Terms terms) { entityManager.persist(terms); }
	public Optional<Terms> lock(long id) {
		return Optional.ofNullable(entityManager.find(Terms.class, id, LockModeType.PESSIMISTIC_WRITE));
	}
	public Instant currentTime() {
		// Use the same database clock as current/upcoming and signup eligibility queries.
		return entityManager.unwrap(Session.class)
				.createNativeQuery("SELECT statement_timestamp()", OffsetDateTime.class)
				.getSingleResult().toInstant();
	}
	public void flush() { entityManager.flush(); }
}
