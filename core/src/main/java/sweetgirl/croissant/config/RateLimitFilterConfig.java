package sweetgirl.croissant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.global.ratelimit.RateLimitFilter;
import sweetgirl.croissant.global.ratelimit.RateLimitProperties;

/**
 * {@link RateLimitFilter} 등록 — Security 체인(-100)보다 앞이라 막힌 요청은 JWT 검증을 타지 않는다.
 * 쿠폰 품절 필터와 같은 이유로 Filter 빈이 아니라 여기서 만든다(빈이면 전역으로 한 번 더 등록된다).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(name = "ops.rate-limit.enabled", havingValue = "true")
public class RateLimitFilterConfig {

	private static final int ORDER_BEFORE_SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 2;

	@Bean
	public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
			RateLimitProperties properties, ObjectMapper objectMapper) {
		FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
			new RateLimitFilter(properties, objectMapper));
		registration.addUrlPatterns("/auth/*", "/stores/search", "/stores/autocomplete");
		registration.setOrder(ORDER_BEFORE_SECURITY);
		registration.setName("rateLimitFilter");
		return registration;
	}
}
