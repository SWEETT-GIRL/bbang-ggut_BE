package sweetgirl.croissant.config.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer 애노테이션 기반 계측 설정.
 *
 * <p>{@code @Timed} / {@code @Counted}는 Spring AOP 프록시를 통해 동작하므로
 * 해당 Aspect 빈이 등록되어 있어야 지표가 수집된다.
 */
@Configuration
public class MetricsConfig {

	@Bean
	public TimedAspect timedAspect(MeterRegistry meterRegistry) {
		return new TimedAspect(meterRegistry);
	}

	@Bean
	public CountedAspect countedAspect(MeterRegistry meterRegistry) {
		return new CountedAspect(meterRegistry);
	}
}
