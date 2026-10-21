package sweetgirl.croissant.coupon.issuer;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.entity.CouponIssue;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * <b>대비군이다. 운영 코드가 아니라서 테스트 소스에만 있다.</b>
 *
 * <p>{@link PessimisticCouponIssuer} 와 모든 것이 같고 <b>락만 없다</b> — {@code findWithLockById} 대신
 * {@code findById} 를 쓴다. 그래서 읽은 값과 쓰는 값 사이가 비어 있고, 그 틈으로 초과 발급이 난다.
 *
 * <p>이걸 두는 이유: 비관적 락 테스트가 "정확히 100장"으로 통과해도, 그게 락 덕분인지 애초에 경합이
 * 안 일어난 건지 알 수 없다. 같은 조건에서 락만 빼면 깨지는 것을 보여야 락이 일하고 있다는 게 증명된다.
 *
 * <p>스프링 빈으로 등록하지 않는다({@code @Component} 없음) — 테스트에서 {@code @Import} 로만 쓴다.
 * 운영 컨텍스트에 올라가면 {@code CouponIssuer} 구현이 둘이 되어 주입이 깨지고, 무엇보다 이건 틀린 구현이다.
 */
@RequiredArgsConstructor
public class NoLockCouponIssuer implements CouponIssuer {

	private static final String ONE_PER_MEMBER_CONSTRAINT = "uq_coupon_issue";

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	@Transactional
	public CouponIssue issue(Long couponId, Long memberId, LocalDateTime now) {
		// 락 없이 읽는다 — 여기가 비관적 락 구현과 다른 유일한 줄이다.
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

		if (coupon.isNotOpenedAt(now)) {
			throw new CouponException(CouponErrorCode.COUPON_NOT_OPENED);
		}
		if (coupon.isSoldOut()) {
			throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
		}

		coupon.issueOne();
		try {
			return couponIssueRepository.saveAndFlush(CouponIssue.of(couponId, memberId, now));
		} catch (DataIntegrityViolationException e) {
			String message = e.getMostSpecificCause().getMessage();
			if (message != null && message.contains(ONE_PER_MEMBER_CONSTRAINT)) {
				throw new CouponException(CouponErrorCode.COUPON_ALREADY_ISSUED, e);
			}
			throw e;
		}
	}
}
