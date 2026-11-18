package kr.fishingstory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionImageSettingTests {
	/** 실행 환경에 R2_ENABLED가 설정되어 있으면 이 테스트는 의미가 없다. 개발 .env는 dev 프로필에서만 읽는다. */
	@Test
	void productionStartupRequiresAnExplicitPhotoStorageChoice() {
		var application = new SpringApplicationBuilder(FishingstoryApiApplication.class)
				.profiles("prod").web(WebApplicationType.NONE)
				.properties("spring.main.banner-mode=off");
		// Bean 조건 평가 단계에서 DB·Valkey 연결 전에 실패해야 한다.
		assertThatThrownBy(() -> application.run()).hasStackTraceContaining("R2_ENABLED");
	}
}
