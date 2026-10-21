package sweetgirl.croissant.dev.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.dev.dto.response.DevCouponResetResponse;
import sweetgirl.croissant.dev.service.DevCouponService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DevCouponControllerTest {

	private static final String ACCESS_KEY = "perf-key";

	private MockMvc mockMvc;

	@Mock
	private DevCouponService devCouponService;
	@Mock
	private Environment environment;

	@InjectMocks
	private DevCouponController devCouponController;

	private void setUpWith(String[] profiles, boolean dashboardEnabled, String configuredKey) {
		given(environment.getActiveProfiles()).willReturn(profiles);
		ReflectionTestUtils.setField(devCouponController, "perfDashboardEnabled", dashboardEnabled);
		ReflectionTestUtils.setField(devCouponController, "perfDashboardAccessKey", configuredKey);
		mockMvc = MockMvcBuilders
			.standaloneSetup(devCouponController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Nested
	@DisplayName("local 에서는")
	class OnLocal {

		@BeforeEach
		void setUp() {
			setUpWith(new String[] {"local"}, false, "");
		}

		@Test
		@DisplayName("키 없이도 초기화되고 k6 가 쓸 couponId 를 돌려준다")
		void resetLoadTestCoupon_returnsCouponId() throws Exception {
			given(devCouponService.reset(eq(100))).willReturn(new DevCouponResetResponse(5L, 100, 900));

			mockMvc.perform(post("/dev/coupons/reset").param("totalQuantity", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.couponId").value(5))
				.andExpect(jsonPath("$.data.totalQuantity").value(100))
				.andExpect(jsonPath("$.data.deletedIssues").value(900));
		}

		@Test
		@DisplayName("재고를 안 주면 기본 100장으로 초기화한다")
		void resetLoadTestCoupon_defaultsTo100() throws Exception {
			given(devCouponService.reset(eq(100))).willReturn(new DevCouponResetResponse(5L, 100, 0));

			mockMvc.perform(post("/dev/coupons/reset"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalQuantity").value(100));
		}
	}

	/**
	 * local 밖에서는 대시보드 키가 유일한 문이다. {@code GET /dev/**} 가 permitAll 이라
	 * 이 검사가 뚫리면 스테이징에서 누구나 발급 내역을 지울 수 있게 된다.
	 */
	@Nested
	@DisplayName("staging 에서는")
	class OnStaging {

		@Test
		@DisplayName("키가 맞으면 초기화된다")
		void withValidKey_resets() throws Exception {
			setUpWith(new String[] {"staging"}, true, ACCESS_KEY);
			given(devCouponService.reset(eq(500))).willReturn(new DevCouponResetResponse(7L, 500, 0));

			mockMvc.perform(post("/dev/coupons/reset")
				.header("X-Perf-Dashboard-Key", ACCESS_KEY)
				.param("totalQuantity", "500"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.couponId").value(7));
		}

		@Test
		@DisplayName("키가 없으면 403 — 서비스는 불리지도 않는다")
		void withoutKey_isForbidden() throws Exception {
			setUpWith(new String[] {"staging"}, true, ACCESS_KEY);

			mockMvc.perform(post("/dev/coupons/reset"))
				.andExpect(status().isForbidden());
			then(devCouponService).should(never()).reset(anyInt());
		}

		@Test
		@DisplayName("키가 틀리면 403")
		void withWrongKey_isForbidden() throws Exception {
			setUpWith(new String[] {"staging"}, true, ACCESS_KEY);

			mockMvc.perform(post("/dev/coupons/reset").header("X-Perf-Dashboard-Key", "틀린키"))
				.andExpect(status().isForbidden());
			then(devCouponService).should(never()).reset(anyInt());
		}

		@Test
		@DisplayName("대시보드가 꺼져 있으면 키가 맞아도 403 — 그 환경은 통째로 닫힌다")
		void whenDashboardDisabled_isForbidden() throws Exception {
			setUpWith(new String[] {"staging"}, false, ACCESS_KEY);

			mockMvc.perform(post("/dev/coupons/reset").header("X-Perf-Dashboard-Key", ACCESS_KEY))
				.andExpect(status().isForbidden());
			then(devCouponService).should(never()).reset(anyInt());
		}
	}
}
