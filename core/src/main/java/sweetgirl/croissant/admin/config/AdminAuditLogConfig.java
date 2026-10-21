package sweetgirl.croissant.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.admin.filter.AdminAuditLogFilter;
import sweetgirl.croissant.admin.service.AdminAuditLogCommandService;

@Configuration
@ConditionalOnProperty(name = "admin.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AdminAuditLogConfig {

	/** AdminApiKeyConfig(HIGHEST_PRECEDENCE + 10) 바로 뒤 — 열쇠를 통과한 요청만 남긴다. */
	private static final int ORDER_AFTER_ADMIN_KEY = Ordered.HIGHEST_PRECEDENCE + 11;

	@Bean
	public FilterRegistrationBean<AdminAuditLogFilter> adminAuditLogFilter(
			AdminAuditLogCommandService commandService, ObjectMapper objectMapper,
			@Value("${admin.audit.key-label:}") String keyLabel) {
		FilterRegistrationBean<AdminAuditLogFilter> bean = new FilterRegistrationBean<>(
			new AdminAuditLogFilter(commandService, objectMapper, keyLabel));
		bean.addUrlPatterns("/api/admin/*", "/admin/*");
		bean.setOrder(ORDER_AFTER_ADMIN_KEY);
		bean.setName("adminAuditLogFilter");
		return bean;
	}
}
