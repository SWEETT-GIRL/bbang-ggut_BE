package sweetgirl.croissant.coupon.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.coupon.entity.CouponIssue;

/**
 * 발급 성공 응답. 실패는 전부 {@code ApiResponse.fail} 의 에러코드로 나간다.
 */
public record CouponIssueResponse(
		Long couponIssueId,
		Long couponId,
		LocalDateTime issuedAt) {

	public static CouponIssueResponse from(CouponIssue couponIssue) {
		return new CouponIssueResponse(
			couponIssue.getId(),
			couponIssue.getCouponId(),
			couponIssue.getIssuedAt());
	}
}
