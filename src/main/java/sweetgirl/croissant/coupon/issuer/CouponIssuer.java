package sweetgirl.croissant.coupon.issuer;

import java.time.LocalDateTime;

import sweetgirl.croissant.coupon.entity.CouponIssue;

/**
 * 쿠폰 한 장을 발급한다. 동시성 제어 방식마다 구현이 다르다.
 *
 * <p>인터페이스를 둔 이유는 확장점이 필요해서가 아니라, <b>락 없는 구현을 대비군으로 끼우기 위해서</b>다.
 * 같은 동시성 테스트를 두 구현에 돌려 "락이 없으면 깨지고 걸면 지켜진다"를 보인다.
 *
 * <p>구현체는 <b>스스로 트랜잭션을 연다.</b> 비관적 락은 트랜잭션이 끝날 때 풀리므로,
 * 락 획득부터 발급 행 INSERT 까지가 한 트랜잭션 안에 있어야 한다.
 */
public interface CouponIssuer {

	/**
	 * @throws sweetgirl.croissant.coupon.exception.CouponException 쿠폰이 없거나, 아직 안 열렸거나, 소진됐거나,
	 *     이미 받았거나, 락 대기가 타임아웃된 경우
	 */
	CouponIssue issue(Long couponId, Long memberId, LocalDateTime now);
}
