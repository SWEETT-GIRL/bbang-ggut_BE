package sweetgirl.croissant.dev.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevCouponResetResponse;
import sweetgirl.croissant.dev.service.DevCouponService;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 부하 측정용 쿠폰 초기화. <b>local 프로파일에서만 뜬다.</b>
 *
 * <p>{@code DevPerfController} 와 달리 staging·prod 에 올리지 않는다 — 저쪽은 읽기 전용 조회지만
 * 이건 발급 내역을 지우는 쓰기다. 프로파일 하나가 유일한 방어선이 되는 상황을 만들지 않는다.
 */
@Profile("local")
@Tag(name = "[Dev] 부하 측정", description = "측정 회차 사이에 쿠폰을 초기화한다 (local 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/dev/coupons")
public class DevCouponController {

	private final DevCouponService devCouponService;

	@Operation(summary = "측정용 쿠폰 초기화", description = "발급 내역을 지우고 재고를 되돌린다. 없으면 만든다. 응답의 couponId 를 k6 가 쓴다.")
	@PostMapping("/reset")
	public ApiResponse<DevCouponResetResponse> resetLoadTestCoupon(
			@RequestParam(defaultValue = "100") int totalQuantity) {
		return ApiResponse.ok(devCouponService.reset(totalQuantity));
	}
}
