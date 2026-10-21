package sweetgirl.croissant.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.coupon.dto.response.CouponIssueResponse;
import sweetgirl.croissant.coupon.service.CouponCommandService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "[Client] 선착순 쿠폰", description = "재고가 정해진 쿠폰의 선착순 발급")
@RestController
@RequiredArgsConstructor
@RequestMapping("/coupons")
public class CouponController {

	private final CouponCommandService couponCommandService;

	@Operation(summary = "쿠폰 발급", description = """
			재고가 남아 있으면 한 장 발급한다. 회원당 한 장까지다.

			실패 응답은 원인별로 코드가 다르다 —
			COUPON_SOLD_OUT(409) 소진, COUPON_ALREADY_ISSUED(409) 이미 받음,
			COUPON_NOT_OPENED(400) 아직 안 열림, COUPON_ISSUE_BUSY(503) 발급이 몰려 대기가 밀림.
			503 은 재고가 없다는 뜻이 아니므로 잠시 후 재시도할 수 있다.
			""")
	@PostMapping("/{couponId}/issue")
	public ResponseEntity<ApiResponse<CouponIssueResponse>> issueCoupon(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long couponId) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(couponCommandService.issue(couponId, memberId)));
	}
}
