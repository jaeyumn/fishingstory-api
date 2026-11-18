package kr.fishingstory.magazine.command.infrastructure.jpa;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import kr.fishingstory.magazine.command.domain.Magazine;

@Repository
public class MagazineStore {
    @PersistenceContext private EntityManager em;
    public Optional<Magazine> bySource(UUID source) {
        return em.createQuery("select m from Magazine m where m.studioId=:source",Magazine.class)
            .setParameter("source",source).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }
    public Optional<Magazine> lock(long id){return Optional.ofNullable(em.find(Magazine.class,id,LockModeType.PESSIMISTIC_WRITE));}
    public void insert(Magazine row){em.persist(row);} public void flush(){em.flush();}
}
