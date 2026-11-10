package sweetgirl.croissant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import sweetgirl.croissant.course.draft.CourseDraftProperties;
import sweetgirl.croissant.course.draft.MemoDraftLoadShedFilter;

/**
 * {@link MemoDraftLoadShedFilter} 등록 — Security 체인(-100)보다 앞이라 거절된 요청은 JWT 검증을 타지 않는다.
 * {@code course.draft.max-concurrent-writes} 가 0 이하면 등록하지 않는다(상한 없음). Filter 빈이 아니라 여기서 만드는 이유는
 * 요청 제한 필터와 같다 — 빈이면 전역으로 한 번 더 등록된다.
 */
@Configuration
@ConditionalOnExpression("${course.draft.max-concurrent-writes:0} > 0")
public class MemoDraftLoadShedFilterConfig {

	private static final int ORDER_BEFORE_SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1;

	@Bean
	public FilterRegistrationBean<MemoDraftLoadShedFilter> memoDraftLoadShedFilterRegistration(
			CourseDraftProperties properties, ObjectMapper objectMapper, MeterRegistry registry) {
		FilterRegistrationBean<MemoDraftLoadShedFilter> registration = new FilterRegistrationBean<>(
			new MemoDraftLoadShedFilter(properties.getMaxConcurrentWrites(), objectMapper, registry));
		registration.addUrlPatterns("/courses/*");
		registration.setOrder(ORDER_BEFORE_SECURITY);
		registration.setName("memoDraftLoadShedFilter");
		return registration;
	}
}
