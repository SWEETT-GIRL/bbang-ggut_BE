package sweetgirl.croissant.dev.dto.response;

/**
 * 측정용 쿠폰 초기화 결과. {@code couponId} 를 k6 스크립트가 그대로 쓴다.
 *
 * @param deletedIssues 지운 발급 내역 수 — 직전 회차가 몇 장 나갔는지 확인하는 용도다
 */
public record DevCouponResetResponse(
		Long couponId,
		int totalQuantity,
		int deletedIssues) {
}
