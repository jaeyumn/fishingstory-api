package kr.fishingstory.terms.infrastructure;

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

/** TODO: 출시 전에 검토된 실제 약관을 새 버전으로 발행하고 개발용 seed를 비활성화한다. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.dev.seed-terms", havingValue = "true")
public class DevTermsSeeder implements ApplicationRunner {
	@PersistenceContext private EntityManager entityManager;
	private final SnowflakeIdGenerator ids;
	private final TransactionTemplate transaction;
	public DevTermsSeeder(SnowflakeIdGenerator ids, PlatformTransactionManager manager) {
		this.ids = ids;
		this.transaction = new TransactionTemplate(manager);
	}
	@Override
	public void run(ApplicationArguments args) {
		transaction.executeWithoutResult(status -> {
			insert("TERMS_OF_SERVICE", "[개발용 임시] 이용약관", true, """
					# 개발용 임시 이용약관

					회원가입·약관 동의 동작을 확인하기 위한 임시 데이터입니다.
					실제 서비스 이용약관으로 사용하지 않습니다.

					TODO: 출시 전 서비스 제공·이용 제한·탈퇴·책임 등에 관한 검토된 실제 약관을 새 버전으로 발행합니다.
					이미 저장된 임시 동의 이력과 버전은 임의로 덮어쓰지 않습니다.
					""");
			insert("PRIVACY_POLICY", "[개발용 임시] 개인정보처리방침", false, """
					# 개발용 임시 개인정보처리방침

					개인정보처리방침 표시 동작을 확인하기 위한 임시 데이터입니다.
					실제 개인정보처리방침으로 사용하지 않으며 포괄 동의 대상이 아닙니다.

					TODO: 출시 전 실제 수집 항목·이용 목적·보존 및 삭제·처리위탁·문의 경로를 확인하여 새 버전으로 발행합니다.
					""");
		});
	}
	private void insert(String type, String title, boolean consent, String content) {
		entityManager.createNativeQuery("""
				INSERT INTO fishingstory.terms
				(terms_id, policy_type, version, title, content, requires_consent, is_required, effective_at, published_at)
				SELECT :id, :type, 'dev-placeholder-1', :title, :content, :consent, :consent,
				       statement_timestamp(), statement_timestamp()
				WHERE NOT EXISTS (
				  SELECT 1 FROM fishingstory.terms WHERE policy_type = :type
				    AND published_at <= statement_timestamp() AND effective_at <= statement_timestamp())
				ON CONFLICT DO NOTHING
				""").setParameter("id", ids.nextId()).setParameter("type", type).setParameter("title", title)
				.setParameter("content", content).setParameter("consent", consent).executeUpdate();
	}
}
