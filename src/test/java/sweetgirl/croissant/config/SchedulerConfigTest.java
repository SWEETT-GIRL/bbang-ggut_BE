package sweetgirl.croissant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulerConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(SchedulerConfig.class, TickingJobConfig.class);

	@Test
	@DisplayName("값을 주지 않으면 예약 작업이 돈다 — 평소 동작은 그대로다")
	void scheduling_isEnabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
			assertThat(context).doesNotHaveBean(SchedulerConfig.SchedulingDisabledNotice.class);
			assertThat(context.getBean(TickingJob.class).ticked.await(3, TimeUnit.SECONDS)).isTrue();
		});
	}

	@Test
	@DisplayName("true 로 주면 예약 작업이 돈다")
	void scheduling_runsWhenEnabled() {
		contextRunner.withPropertyValues("app.scheduling.enabled=true").run(context -> {
			assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
			assertThat(context.getBean(TickingJob.class).ticked.await(3, TimeUnit.SECONDS)).isTrue();
		});
	}

	@Test
	@DisplayName("false 로 주면 @Scheduled 메서드가 한 번도 돌지 않고, 꺼져 있다는 경고 빈이 뜬다")
	void scheduling_doesNotRunWhenDisabled() {
		contextRunner.withPropertyValues("app.scheduling.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
			assertThat(context).hasSingleBean(SchedulerConfig.SchedulingDisabledNotice.class);
			assertThat(context.getBean(TickingJob.class).ticked.await(1, TimeUnit.SECONDS)).isFalse();
		});
	}

	@Configuration
	static class TickingJobConfig {

		@Bean
		TickingJob tickingJob() {
			return new TickingJob();
		}
	}

	static class TickingJob {

		final CountDownLatch ticked = new CountDownLatch(1);

		@Scheduled(fixedDelay = 50)
		public void tick() {
			ticked.countDown();
		}
	}
}
