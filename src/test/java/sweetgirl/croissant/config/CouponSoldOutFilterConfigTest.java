package sweetgirl.croissant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;

/**
 * 스위치가 실제로 동작하는지 본다.
 *
 * <p>이 스위치의 존재 이유는 측정이다 — <b>필터 없이 플래그만 있는 상태</b>를 재현해 필터의
 * 기여도를 분리한다. 스위치가 조용히 무시되면 "필터를 끄고 쟀다"고 믿으면서 실제로는 켜진 상태를
 * 재게 되고, 그건 측정을 통째로 버려야 하는 종류의 오류다.
 */
class CouponSoldOutFilterConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(ObjectMapper.class, ObjectMapper::new)
		.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
		.withBean(CouponSoldOutFlags.class, () -> new CouponSoldOutFlags(true))
		.withUserConfiguration(CouponSoldOutFilterConfig.class);

	@Test
	@DisplayName("설정이 없으면 필터가 등록된다 — 운영 기본값은 켜짐")
	void registersByDefault() {
		contextRunner.run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
	}

	@Test
	@DisplayName("true 면 등록된다")
	void registersWhenEnabled() {
		contextRunner
			.withPropertyValues("coupon.sold-out-filter.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
	}

	@Test
	@DisplayName("false 면 등록되지 않는다 — 필터 없는 상태를 재측정하기 위한 스위치")
	void doesNotRegisterWhenDisabled() {
		contextRunner
			.withPropertyValues("coupon.sold-out-filter.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
	}

	@Test
	@DisplayName("Security 필터 체인(-100)보다 앞에 등록된다 — 뒤면 조용히 무력화된다")
	void registersBeforeSecurityFilterChain() {
		contextRunner.run(context -> {
			FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
			assertThat(registration.getOrder()).isLessThan(-100);
		});
	}
}
