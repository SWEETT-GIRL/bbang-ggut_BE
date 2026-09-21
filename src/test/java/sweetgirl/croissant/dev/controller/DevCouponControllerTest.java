package sweetgirl.croissant.dev.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.dev.dto.response.DevCouponResetResponse;
import sweetgirl.croissant.dev.service.DevCouponService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class DevCouponControllerTest {

	private MockMvc mockMvc;

	@Mock
	private DevCouponService devCouponService;

	@InjectMocks
	private DevCouponController devCouponController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(devCouponController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("초기화하면 k6 가 쓸 couponId 를 돌려준다")
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
