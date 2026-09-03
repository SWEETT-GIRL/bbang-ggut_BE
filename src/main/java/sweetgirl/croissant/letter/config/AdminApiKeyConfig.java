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
		bean.addUrlPatterns("/api/admin/letters/*");
		// 인증을 다루므로 요청 로깅보다도 앞이다 — 막을 요청에 일을 시키지 않는다.
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return bean;
	}
}
