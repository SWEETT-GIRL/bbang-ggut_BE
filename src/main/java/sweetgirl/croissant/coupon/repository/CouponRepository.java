package sweetgirl.croissant.coupon.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import sweetgirl.croissant.coupon.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	/**
	 * 쿠폰 행에 비관적 락을 걸고 읽는다({@code SELECT ... FOR UPDATE}). 같은 쿠폰으로 들어오는 발급을 줄 세운다.
	 *
	 * <p>락은 <b>트랜잭션이 끝날 때</b> 풀린다 — 락 획득부터 발급 행 INSERT 까지 한 {@code @Transactional} 안에서 끝내야 한다.
	 * 같은 클래스 안에서 부르면 프록시를 타지 않아 트랜잭션이 없고, 락이 곧바로 풀린다.
	 *
	 * <p>타임아웃 3초 — 없으면 무한히 기다린다. 측정 결과를 보고 조정한다.
	 * 초과하면 {@code LockTimeoutException} 이 난다.
	 *
	 * <p>조회에는 걸지 않는다 — {@code findById} 그대로.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
	Optional<Coupon> findWithLockById(Long id);
}
