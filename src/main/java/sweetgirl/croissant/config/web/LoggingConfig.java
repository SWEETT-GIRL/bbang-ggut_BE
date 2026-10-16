package sweetgirl.croissant.config.web;

import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.filter.ServerTimeHeaderFilter;
import sweetgirl.croissant.global.logging.filter.MaskingRequestLoggingFilter;
import sweetgirl.croissant.global.logging.filter.RequestIdFilter;
import sweetgirl.croissant.global.logging.filter.RequestLoggingFilter;
import sweetgirl.croissant.global.logging.service.RequestLogService;

@Configuration
@RequiredArgsConstructor
public class LoggingConfig {

	private final RequestLogService requestLogService;

	@Bean
	public CommonsRequestLoggingFilter requestLoggingFilter() {
		// 초대·공유 토큰이 URI·쿼리에 실리므로 DEBUG 요청 로그에서도 가린다
		CommonsRequestLoggingFilter filter = new MaskingRequestLoggingFilter();
		filter.setIncludeClientInfo(true);
		filter.setIncludeQueryString(true);
		filter.setIncludePayload(true);
		filter.setMaxPayloadLength(10000);
		filter.setIncludeHeaders(false);
		return filter;
	}

	@Bean
	public FilterRegistrationBean<RequestIdFilter> requestIdFilterBean() {
		FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
		bean.addUrlPatterns("/*");
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE); // 가장 바깥 — 뒤쪽 필터들의 로그에도 requestId 가 붙는다
		bean.setName("requestIdFilter");
		return bean;
	}

	@Bean
	public FilterRegistrationBean<ServerTimeHeaderFilter> serverTimeHeaderFilterBean() {
		FilterRegistrationBean<ServerTimeHeaderFilter> bean = new FilterRegistrationBean<>(
			new ServerTimeHeaderFilter(Clock.systemUTC()));
		bean.addUrlPatterns("/*");
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1); // 429·401 처럼 앞단에서 끝나는 응답에도 붙도록 바깥에 둔다
		bean.setName("serverTimeHeaderFilter");
		return bean;
	}

	@Bean
	public FilterRegistrationBean<RequestLoggingFilter> requestLogFilterBean() {
		FilterRegistrationBean<RequestLoggingFilter> bean = new FilterRegistrationBean<>(); //우리가 만든 필터를 서블릿 컨테이너에 등록
		bean.setFilter(new RequestLoggingFilter(requestLogService));
		bean.addUrlPatterns("/*"); // 모든 경로에 적용
		bean.setOrder(Ordered.LOWEST_PRECEDENCE); // Security(-100)보다 뒤에 실행 → SecurityContextHolder 사용 가능
		return bean;
	}
}
