package kr.fishingstory.auth.infrastructure.jpa;

import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import kr.fishingstory.auth.domain.UserAuth;
import kr.fishingstory.terms.domain.Terms;
import kr.fishingstory.terms.domain.UserTermsAgreement;
import kr.fishingstory.user.domain.User;

@Repository
public class SignupMemberStore {
	@PersistenceContext private EntityManager entityManager;

	public Optional<Terms> findCurrentTermsOfService() {
		return entityManager.unwrap(Session.class).createNativeQuery("""
				SELECT * FROM fishingstory.terms
				WHERE policy_type = 'TERMS_OF_SERVICE'
				  AND published_at <= statement_timestamp()
				  AND effective_at <= statement_timestamp()
				ORDER BY effective_at DESC
				LIMIT 1
				""", Terms.class).uniqueResultOptional();
	}

	public void insert(User user, UserAuth auth, UserTermsAgreement agreement) {
		// 지정 ID 충돌에서 기존 행을 갱신할 수 있는 merge를 사용하지 않는다.
		entityManager.persist(user);
		entityManager.persist(auth);
		entityManager.persist(agreement);
		entityManager.flush();
	}
}
