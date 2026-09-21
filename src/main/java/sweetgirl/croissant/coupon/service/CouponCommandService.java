package sweetgirl.croissant.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.coupon.dto.response.CouponIssueResponse;
import sweetgirl.croissant.coupon.entity.CouponIssue;
import sweetgirl.croissant.coupon.issuer.CouponIssuer;

/**
 * 쿠폰 발급의 진입점. 발급 시각을 정하고, 엔티티를 DTO 로 바꾼다.
 *
 * <p><b>여기에는 {@code @Transactional} 을 붙이지 않는다.</b> 트랜잭션은 {@link CouponIssuer} 구현이 연다 —
 * 비관적 락은 트랜잭션이 끝날 때 풀리므로, 트랜잭션을 여기서 열면 락 구간이 이 메서드 전체로 늘어난다.
 * 지금은 DTO 변환뿐이라 차이가 미미하지만, 나중에 발급 이벤트 발행이나 외부 호출(알림·분석)이 붙으면
 * 그게 전부 <b>락을 쥔 채로</b> 일어난다. 처리량이 락 보유 시간에 반비례하는 구조라 그 문을 열어두지 않는다.
 *
 * <p>쓰기 서비스에 {@code @Transactional} 을 두는 프로젝트 관례와 다르다 — 위 이유 때문에 일부러 뺀 것이다.
 */
@Service
@RequiredArgsConstructor
public class CouponCommandService {

	private final CouponIssuer couponIssuer;

	public CouponIssueResponse issue(Long couponId, Long memberId) {
		CouponIssue couponIssue = couponIssuer.issue(couponId, memberId, LocalDateTime.now());
		return CouponIssueResponse.from(couponIssue);
	}
}
