package sweetgirl.croissant.letter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AdminApiKeyConfig {

	@Bean
	public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilter(
			@Value("${admin.api-key:}") String apiKey) {
		FilterRegistrationBean<AdminApiKeyFilter> bean = new FilterRegistrationBean<>();
		bean.setFilter(new AdminApiKeyFilter(apiKey));
		// AdminApiKeyFilter.PROTECTED_PREFIXES 와 짝 — 한쪽만 더하면 필터가 안 걸리거나 헛돈다
		bean.addUrlPatterns("/api/admin/letters/*", "/admin/personalization/*", "/admin/recommendations/*");
		// 인증을 다루므로 요청 로깅보다도 앞이다 — 막을 요청에 일을 시키지 않는다.
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return bean;
	}
}
