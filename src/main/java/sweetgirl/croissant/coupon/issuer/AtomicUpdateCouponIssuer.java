package sweetgirl.croissant.coupon.issuer;

import java.time.LocalDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.entity.CouponIssue;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * 재고 검사를 SQL 안으로 넣어, 소진된 뒤의 요청이 <b>락을 잡지 않게</b> 한다.
 *
 * <p>비관적 락과의 차이는 "잠그고 본다"와 "보고 잠근다"이다. {@code SELECT ... FOR UPDATE} 에는 조건이
 * 없어서 답이 "품절"인 게 뻔한 요청도 전부 행을 잠그고 줄을 선다. 요청의 대부분이 소진 경로라
 * 그 줄이 곧 처리량의 천장이 된다.
 *
 * <p>여기서는 조건이 {@code WHERE} 안에 있다. 거짓이면 바꿀 행이 없으니 잠글 것도 없고,
 * 소진된 뒤의 요청들은 서로 직렬화되지 않는다.
 *
 * <p>설정 {@code coupon.issuer=atomic} 일 때만 등록된다. 비관적 락 구현을 지우지 않는 이유는
 * 같은 시나리오로 다시 비교할 수 있어야 하기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "coupon.issuer", havingValue = "atomic")
@RequiredArgsConstructor
public class AtomicUpdateCouponIssuer implements CouponIssuer {

	/** 마이그레이션 V20260921020149 에서 {@code coupon_issues} 에 건 유니크 제약 이름. */
	private static final String ONE_PER_MEMBER_CONSTRAINT = "uq_coupon_issue";

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponSoldOutFlags soldOutFlags;

	@Override
	@Transactional
	public CouponIssue issue(Long couponId, Long memberId, LocalDateTime now) {
		// 관문 ② — 커넥션을 기다리는 동안 소진됐을 수 있다. 깨어나자마자 확인하고 DB 왕복 없이 돌아선다.
		// 커넥션을 나노초만 쥐고 반납하므로 뒤에 줄 선 요청들이 그만큼 빨리 빠진다.
		if (soldOutFlags.isSoldOut(couponId)) {
			throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
		}

		rejectEarly(couponId, now);

		// 최종 판정. 여기까지의 검사는 전부 스냅샷 기준이라 경계에서는 틀릴 수 있고, 이 한 문장만 정확하다.
		if (couponRepository.increaseIssuedQuantity(couponId, now) == 0) {
			soldOutFlags.markSoldOut(couponId);
			throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
		}
		return saveIssue(couponId, memberId, now);
	}

	/**
	 * 락 없이 한 번 읽어 답이 뻔한 요청을 먼저 돌려보낸다.
	 *
	 * <p>이 읽기가 없으면 소진된 요청도 UPDATE 를 한 번 날리고 0행을 받은 뒤, 사유(소진인지 미오픈인지)를
	 * 가리려고 또 읽어야 한다 — 거절 경로가 왕복 두 번이 된다. 먼저 읽으면 한 번으로 끝난다.
	 *
	 * <p>MVCC 라 이 읽기는 락을 잡지 않는다. 스냅샷이라 소진 직전에는 틀릴 수 있지만,
	 * 그 경우에도 위의 {@code increaseIssuedQuantity} 가 최종 판정을 하므로 초과 발급은 불가능하다.
	 */
	private void rejectEarly(Long couponId, LocalDateTime now) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

		if (coupon.isNotOpenedAt(now)) {
			throw new CouponException(CouponErrorCode.COUPON_NOT_OPENED);
		}
		if (coupon.isSoldOut()) {
			soldOutFlags.markSoldOut(couponId);
			throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
		}
	}

	private CouponIssue saveIssue(Long couponId, Long memberId, LocalDateTime now) {
		try {
			return couponIssueRepository.saveAndFlush(CouponIssue.of(couponId, memberId, now));
		} catch (DataIntegrityViolationException e) {
			if (violatesOnePerMember(e)) {
				throw new CouponException(CouponErrorCode.COUPON_ALREADY_ISSUED, e);
			}
			throw e;
		}
	}

	private boolean violatesOnePerMember(DataIntegrityViolationException exception) {
		if (exception.getCause() instanceof ConstraintViolationException violation
			&& ONE_PER_MEMBER_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
			return true;
		}
		String message = exception.getMostSpecificCause().getMessage();
		return message != null && message.contains(ONE_PER_MEMBER_CONSTRAINT);
	}
}
