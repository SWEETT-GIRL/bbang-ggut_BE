package sweetgirl.croissant.coupon.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

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

	/**
	 * 재고가 남아 있을 때만 발급 수를 하나 늘린다. <b>검사와 증가가 한 문장</b>이라 그 사이에 끼어들 수 없다.
	 *
	 * <p>{@code findWithLockById} 와 결정적으로 다른 점은 <b>조건이 SQL 안에 있다</b>는 것이다.
	 * {@code SELECT ... FOR UPDATE} 는 조건 없이 행을 먼저 잠근 뒤 애플리케이션이 검사하므로,
	 * 답이 "품절"인 게 뻔한 요청도 전부 락을 잡고 줄을 선다. 이쪽은 조건이 거짓이면 바꿀 행이 없어
	 * <b>잠글 것도 없다</b> — 소진된 뒤의 요청들이 서로 직렬화되지 않는다.
	 *
	 * <p>(다만 다른 트랜잭션이 그 행을 수정 중이면 PostgreSQL 이 조건을 최신 값으로 다시 평가하려고
	 * 기다린다. 소진이 확정되기 전, 경합이 있는 구간에서는 이쪽도 줄을 선다.)
	 *
	 * <p>벌크 연산이라 감사(auditing)가 동작하지 않아 {@code updatedAt} 을 직접 넣는다.
	 * 영속성 컨텍스트와 어긋나지 않도록 실행 후 비운다.
	 *
	 * @return 1이면 발급, 0이면 소진(또는 아직 안 열림 · 없는 쿠폰)
	 */
	@Modifying(clearAutomatically = true)
	@Query("update Coupon c set c.issuedQuantity = c.issuedQuantity + 1, c.updatedAt = :now "
		+ "where c.id = :couponId and c.issuedQuantity < c.totalQuantity and c.opensAt <= :now")
	int increaseIssuedQuantity(@Param("couponId") Long couponId, @Param("now") LocalDateTime now);
}
