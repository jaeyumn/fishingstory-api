package kr.fishingstory.inquiry.command.infrastructure.jpa;

import java.util.Optional;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import kr.fishingstory.inquiry.command.domain.*;

@Repository
public class InquiryStore {
	@PersistenceContext private EntityManager entityManager;
	public void insert(Inquiry inquiry) { entityManager.persist(inquiry); }
	public void insert(InquiryMessage message) { entityManager.persist(message); }
	public Optional<Inquiry> lock(long id) {
		return Optional.ofNullable(entityManager.find(Inquiry.class, id, LockModeType.PESSIMISTIC_WRITE));
	}
	public Optional<Inquiry> requested(long user, String key) {
		return entityManager.createQuery("SELECT i FROM Inquiry i WHERE i.userId=:user AND i.requestKey=:key", Inquiry.class)
				.setParameter("user", user).setParameter("key", key).getResultStream().findFirst();
	}
	public Optional<InquiryMessage> requestedMessage(long inquiry, long sender, String key) {
		return entityManager.createQuery("SELECT m FROM InquiryMessage m WHERE m.inquiryId=:inquiry AND m.senderId=:sender AND m.requestKey=:key", InquiryMessage.class)
				.setParameter("inquiry", inquiry).setParameter("sender", sender).setParameter("key", key).getResultStream().findFirst();
	}
}
