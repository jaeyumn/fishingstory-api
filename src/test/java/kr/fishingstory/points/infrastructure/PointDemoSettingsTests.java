package kr.fishingstory.points.infrastructure;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PointDemoSettingsTests {
	@Test void demoQueriesRequireExplicitOptInAndDevWithoutProd() {
		for (String[] profiles : new String[][] {{}, {"test"}, {"prod"}, {"dev","prod"}, {"dev"}}) {
			var environment = new MockEnvironment();
			environment.setActiveProfiles(profiles);
			assertThat(new PointDemoSettings(environment,false).enabled()).isFalse();
			assertThat(new PointDemoSettings(environment,true).enabled()).isEqualTo(profiles.length==1 && profiles[0].equals("dev"));
		}
	}
	@Test void seederCannotLoadUnderProdEvenWithDevAndPropertyEnabled() {
		var runner = new ApplicationContextRunner().withUserConfiguration(DevPointsSeeder.class)
				.withBean(SnowflakeIdGenerator.class, () -> mock(SnowflakeIdGenerator.class))
				.withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
				.withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
		runner.withPropertyValues("spring.profiles.active=prod", "app.dev.seed-points=true")
				.run(context -> assertThat(context).doesNotHaveBean(DevPointsSeeder.class));
		runner.withPropertyValues("spring.profiles.active=dev,prod", "app.dev.seed-points=true")
				.run(context -> assertThat(context).doesNotHaveBean(DevPointsSeeder.class));
		runner.withPropertyValues("spring.profiles.active=dev", "app.dev.seed-points=false")
				.run(context -> assertThat(context).doesNotHaveBean(DevPointsSeeder.class));
		runner.withPropertyValues("spring.profiles.active=dev", "app.dev.seed-points=true")
				.run(context -> assertThat(context).hasSingleBean(DevPointsSeeder.class));
	}
}
