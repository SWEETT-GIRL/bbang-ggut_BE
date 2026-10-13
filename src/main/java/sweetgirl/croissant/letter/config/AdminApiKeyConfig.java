package sweetgirl.croissant.letter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration
public class AdminApiKeyConfig {

	/** 기존 경로의 로컬 키 생략 설정. 월간 보고서는 환경과 무관하게 키가 필요하다. */
	private static final String LOCAL_PROFILE = "local";

	@Bean
	public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilter(
			@Value("${admin.api-key:}") String apiKey, Environment environment) {
		boolean allowWithoutKey = environment.matchesProfiles(LOCAL_PROFILE);
		FilterRegistrationBean<AdminApiKeyFilter> bean = new FilterRegistrationBean<>();
		bean.setFilter(new AdminApiKeyFilter(apiKey, allowWithoutKey));
		// AdminApiKeyFilter.PROTECTED_PREFIXES 와 짝 — 한쪽만 더하면 필터가 안 걸리거나 헛돈다
		bean.addUrlPatterns("/api/admin/letters/*", "/admin/personalization/*", "/admin/recommendations/*",
			"/api/admin/monthly-reports/*", "/api/admin/tutorial-templates", "/api/admin/tutorial-templates/*",
			"/admin/images/reconcile", "/admin/images/reconcile/*", "/api/admin/world/*");
		// 인증을 다루므로 요청 로깅보다도 앞이다 — 막을 요청에 일을 시키지 않는다.
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return bean;
	}
}
