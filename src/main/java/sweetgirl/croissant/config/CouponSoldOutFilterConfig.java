package sweetgirl.croissant.config;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import sweetgirl.croissant.coupon.filter.CouponSoldOutFilter;
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;

/**
 * {@link CouponSoldOutFilter} 등록.
 *
 * <p>순서가 이 클래스의 존재 이유다. Spring Security 의 필터 체인은
 * {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER}(-100)에 등록되므로 <b>그보다 앞</b>이어야
 * JWT 검증을 건너뛸 수 있다. 뒤에 두면 인증을 태운 다음 거절하게 되어 줄이는 비용이 남는다.
 *
 * <p>URL 패턴을 {@code /coupons/*} 로 좁힌 것도 의도다. 필터를 전역으로 걸면 가게 검색처럼
 * 쿠폰과 무관한 요청도 이 필터를 거치게 되고, 그 요청들을 보호하려고 만든 장치가 그들에게
 * 세금을 매기는 셈이 된다.
 *
 * <p>필터를 {@code Filter} 타입 빈으로 노출하지 않고 여기서 직접 생성한다 — 빈으로 두면
 * Spring Boot 가 전역으로 한 번 더 자동 등록해 두 번 실행된다.
 */
@Configuration
public class CouponSoldOutFilterConfig {

	private static final int ORDER_BEFORE_SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1;

	@Bean
	public FilterRegistrationBean<CouponSoldOutFilter> couponSoldOutFilterRegistration(
			CouponSoldOutFlags soldOutFlags,
			MeterRegistry meterRegistry,
			ObjectMapper objectMapper) {
		FilterRegistrationBean<CouponSoldOutFilter> registration = new FilterRegistrationBean<>(
			new CouponSoldOutFilter(soldOutFlags, meterRegistry, objectMapper));
		registration.addUrlPatterns("/coupons/*");
		registration.setOrder(ORDER_BEFORE_SECURITY);
		registration.setName("couponSoldOutFilter");
		return registration;
	}
}
