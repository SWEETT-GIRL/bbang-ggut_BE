package sweetgirl.croissant.config.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricsConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
		.withUserConfiguration(MetricsConfig.class);

	@Test
	@DisplayName("애노테이션 계측용 Aspect 빈이 등록된다")
	void aspectBeansAreRegistered() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(TimedAspect.class);
			assertThat(context).hasSingleBean(CountedAspect.class);
		});
	}

	@Test
	@DisplayName("클래스 레벨 @Timed가 붙은 빈의 호출이 타이머로 기록된다")
	void classLevelTimedRecordsTimer() {
		contextRunner.withUserConfiguration(TimedTargetConfiguration.class).run(context -> {
			context.getBean(TimedTarget.class).call();

			MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
			assertThat(meterRegistry.get("test.external.api")
				.tag("client", "sample")
				.tag("method", "call")
				.timer()
				.count()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("@Timed 대상 메서드가 예외를 던지면 exception 태그와 함께 기록된다")
	void classLevelTimedRecordsException() {
		contextRunner.withUserConfiguration(TimedTargetConfiguration.class).run(context -> {
			TimedTarget target = context.getBean(TimedTarget.class);

			assertThat(catchThrowable(target)).isInstanceOf(IllegalStateException.class);

			MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
			assertThat(meterRegistry.get("test.external.api")
				.tag("method", "fail")
				.tag("exception", "IllegalStateException")
				.timer()
				.count()).isEqualTo(1);
		});
	}

	private Throwable catchThrowable(TimedTarget target) {
		try {
			target.fail();
			return null;
		} catch (Throwable thrown) {
			return thrown;
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAspectJAutoProxy
	static class TimedTargetConfiguration {

		@Bean
		TimedTarget timedTarget() {
			return new TimedTarget();
		}
	}

	@Timed(value = "test.external.api", extraTags = {"client", "sample"})
	static class TimedTarget {

		String call() {
			return "called";
		}

		void fail() {
			throw new IllegalStateException("계측 확인용 예외");
		}
	}
}
