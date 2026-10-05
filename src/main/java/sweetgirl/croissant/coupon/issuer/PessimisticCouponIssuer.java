package sweetgirl.croissant.coupon.issuer;

import java.time.LocalDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.entity.CouponIssue;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * 쿠폰 행에 비관적 락({@code SELECT ... FOR UPDATE})을 걸어 발급을 줄 세운다.
 *
 * <p>락은 트랜잭션이 끝날 때 풀린다. 그래서 이 메서드 하나가 <b>락 획득 → 재고 검사 → 카운터 증가 → 발급 INSERT</b>
 * 를 전부 안고 있다. 이 구간에 들어가는 왕복 하나하나가 곧 락 보유 시간이고, 그게 처리량을 결정한다
 * (처리량 ≈ 1 ÷ 락 보유 시간). 여기에 쿼리를 더하기 전에 정말 필요한지 따진다.
 *
 * <p>설정 {@code coupon.issuer} 가 없거나 {@code pessimistic} 일 때 등록된다(기본값).
 *
 * <p><b>self-invocation 주의.</b> 같은 클래스 안에서 {@code issue()} 를 부르면 프록시를 타지 않아
 * 트랜잭션이 없고, 락이 곧바로 풀린다. 반드시 빈 바깥에서 호출해야 한다.
 */
@Component
@ConditionalOnProperty(name = "coupon.issuer", havingValue = "pessimistic", matchIfMissing = true)
@RequiredArgsConstructor
public class PessimisticCouponIssuer implements CouponIssuer {

	/** 마이그레이션 V20260921020149 에서 {@code coupon_issues} 에 건 유니크 제약 이름. */
	private static final String ONE_PER_MEMBER_CONSTRAINT = "uq_coupon_issue";

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	@Transactional
	public CouponIssue issue(Long couponId, Long memberId, LocalDateTime now) {
		Coupon coupon = lockCoupon(couponId);

		if (coupon.isNotOpenedAt(now)) {
			throw new CouponException(CouponErrorCode.COUPON_NOT_OPENED);
		}
		if (coupon.isSoldOut()) {
			throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
		}

		coupon.issueOne();
		return saveIssue(couponId, memberId, now);
	}

	private Coupon lockCoupon(Long couponId) {
		try {
			return couponRepository.findWithLockById(couponId)
				.orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
		} catch (PessimisticLockingFailureException | LockTimeoutException | PessimisticLockException e) {
			throw new CouponException(CouponErrorCode.COUPON_ISSUE_BUSY, e);
		}
	}

	/**
	 * 1인 1매를 미리 조회해서 막지 않고 유니크 제약에 맡긴다.
	 *
	 * <p>락을 쥔 동안에는 같은 쿠폰의 발급이 직렬화되므로 사전 조회도 정확하긴 하다. 다만 그 조회 한 번이
	 * 락 보유 시간에 그대로 더해지고, 중복 발급은 드문 경로다 — 흔한 길을 느리게 만들 이유가 없다.
	 * 제약 위반이 나면 트랜잭션이 통째로 롤백되므로 카운터 증가도 함께 되돌아간다.
	 *
	 * <p>{@code uq_coupon_issue} 위반만 중복으로 본다. FK 위반 같은 다른 무결성 오류는 버그이므로 그대로 터뜨린다.
	 */
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

	/**
	 * Hibernate 의 제약 위반은 {@code DuplicateKeyException} 이 아니라 {@code DataIntegrityViolationException}
	 * 으로 번역돼 온다. 그래서 예외 타입만으로는 중복 발급인지 FK 위반인지 가릴 수 없고, 제약 이름을 봐야 한다.
	 * 방언에 따라 이름이 비어 올 수 있어 원인 메시지도 함께 본다.
	 */
	private boolean violatesOnePerMember(DataIntegrityViolationException exception) {
		if (exception.getCause() instanceof ConstraintViolationException violation
			&& ONE_PER_MEMBER_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
			return true;
		}
		String message = exception.getMostSpecificCause().getMessage();
		return message != null && message.contains(ONE_PER_MEMBER_CONSTRAINT);
	}
}
