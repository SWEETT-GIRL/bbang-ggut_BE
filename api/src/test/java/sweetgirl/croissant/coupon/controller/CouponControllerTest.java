package sweetgirl.croissant.coupon.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.dto.response.CouponIssueResponse;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.service.CouponCommandService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

	private static final Long COUPON_ID = 1L;
	private static final Long MEMBER_ID = 7L;

	private MockMvc mockMvc;

	@Mock
	private CouponCommandService couponCommandService;

	@InjectMocks
	private CouponController couponController;

	/**
	 * standaloneSetup 에는 시큐리티가 없어 {@code @AuthenticationPrincipal} 이 null 로 들어온다.
	 * 인증된 회원이 부른 상황을 재현해야 memberId 가 서비스까지 제대로 넘어가는지 볼 수 있다.
	 */
	@BeforeEach
	void setUp() {
		HandlerMethodArgumentResolver authenticatedMember = new HandlerMethodArgumentResolver() {
			@Override
			public boolean supportsParameter(MethodParameter parameter) {
				return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
			}

			@Override
			public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
					NativeWebRequest request, WebDataBinderFactory binderFactory) {
				return MEMBER_ID;
			}
		};
		mockMvc = MockMvcBuilders
			.standaloneSetup(couponController)
			.setCustomArgumentResolvers(authenticatedMember)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("발급에 성공하면 201 과 발급 내역을 돌려준다")
	void issueCoupon_returns201() throws Exception {
		given(couponCommandService.issue(eq(COUPON_ID), eq(MEMBER_ID)))
			.willReturn(new CouponIssueResponse(42L, COUPON_ID, LocalDateTime.of(2026, 9, 21, 12, 0)));

		mockMvc.perform(post("/coupons/{couponId}/issue", COUPON_ID))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.couponIssueId").value(42))
			.andExpect(jsonPath("$.data.couponId").value(1));
	}

	@Test
	@DisplayName("소진된 쿠폰이면 409 COUPON_SOLD_OUT")
	void issueCoupon_returns409_whenSoldOut() throws Exception {
		given(couponCommandService.issue(eq(COUPON_ID), eq(MEMBER_ID)))
			.willThrow(new CouponException(CouponErrorCode.COUPON_SOLD_OUT));

		mockMvc.perform(post("/coupons/{couponId}/issue", COUPON_ID))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"));
	}

	@Test
	@DisplayName("이미 받은 회원이면 409 COUPON_ALREADY_ISSUED — 소진과 코드가 달라야 앱이 문구를 가른다")
	void issueCoupon_returns409_whenAlreadyIssued() throws Exception {
		given(couponCommandService.issue(eq(COUPON_ID), eq(MEMBER_ID)))
			.willThrow(new CouponException(CouponErrorCode.COUPON_ALREADY_ISSUED));

		mockMvc.perform(post("/coupons/{couponId}/issue", COUPON_ID))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("COUPON_ALREADY_ISSUED"));
	}

	@Test
	@DisplayName("락 대기가 밀리면 503 COUPON_ISSUE_BUSY — 재고 없음(409)이 아니라 재시도 가능한 실패다")
	void issueCoupon_returns503_whenBusy() throws Exception {
		given(couponCommandService.issue(eq(COUPON_ID), eq(MEMBER_ID)))
			.willThrow(new CouponException(CouponErrorCode.COUPON_ISSUE_BUSY));

		mockMvc.perform(post("/coupons/{couponId}/issue", COUPON_ID))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("COUPON_ISSUE_BUSY"));
	}

	@Test
	@DisplayName("아직 열리지 않은 쿠폰이면 400 COUPON_NOT_OPENED")
	void issueCoupon_returns400_whenNotOpened() throws Exception {
		given(couponCommandService.issue(eq(COUPON_ID), eq(MEMBER_ID)))
			.willThrow(new CouponException(CouponErrorCode.COUPON_NOT_OPENED));

		mockMvc.perform(post("/coupons/{couponId}/issue", COUPON_ID))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COUPON_NOT_OPENED"));
	}

	@Test
	@DisplayName("없는 쿠폰이면 404 COUPON_NOT_FOUND")
	void issueCoupon_returns404_whenCouponMissing() throws Exception {
		given(couponCommandService.issue(eq(999L), eq(MEMBER_ID)))
			.willThrow(new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

		mockMvc.perform(post("/coupons/{couponId}/issue", 999L))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
	}
}
