package sweetgirl.croissant.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.issuer.NoLockCouponIssuer;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * 대비군: 락을 빼면 <b>정말로</b> 초과 발급이 나는지 본다 (설계 §1 Q1).
 *
 * <p>{@link sweetgirl.croissant.coupon.CouponConcurrencyLabTest} 와 조건이 같다 — 재고 100장, 1000명 동시.
 * 다른 것은 발급자가 {@link NoLockCouponIssuer} 라는 점뿐이다. 이 테스트가 깨진 결과를 보여야
 * 비관적 락 테스트의 "정확히 100장"이 락 덕분이라고 말할 수 있다.
 *
 * <p>경합은 확률이라 초과량이 매번 다르다. 그래서 "몇 장 나갔다"를 고정하지 않고
 * <b>100장을 넘었다</b>만 단언한다. 이 테스트가 통과한다 = 문제가 재현됐다는 뜻이다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, NoLockCouponIssuer.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=true",
	"spring.flyway.out-of-order=true",
	"spring.flyway.baseline-on-migrate=true",
	"spring.flyway.baseline-version=20260604.0",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false"
})
class NoLockCouponIssuerLabTest {

	private static final int STOCK = 100;
	private static final int ATTEMPTS = 1000;
	private static final int THREADS = 64;
	private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 9, 21, 0, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 12, 0);

	@Autowired
	private NoLockCouponIssuer noLockCouponIssuer;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private CouponIssueRepository couponIssueRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long couponId;

	@BeforeEach
	void createCoupon() {
		couponId = couponRepository.save(Coupon.open("랩 대비군 쿠폰", STOCK, OPENED_AT)).getId();
	}

	@AfterEach
	void deleteCoupon() {
		jdbcTemplate.update("DELETE FROM coupon_issues WHERE coupon_id = ?", couponId);
		jdbcTemplate.update("DELETE FROM coupons WHERE id = ?", couponId);
	}

	@Test
	@DisplayName("락이 없으면 재고 100장에 1000명이 몰릴 때 100장을 넘겨 발급된다 — 비관적 락이 막아주는 것이 바로 이것")
	void withoutLock_overIssues() throws InterruptedException {
		List<Long> memberIds = jdbcTemplate.queryForList(
			"SELECT id FROM member ORDER BY id LIMIT ?", Long.class, ATTEMPTS);
		assertThat(memberIds).hasSize(ATTEMPTS);

		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(ATTEMPTS);
		AtomicInteger issued = new AtomicInteger();

		try {
			for (Long memberId : memberIds) {
				executor.submit(() -> {
					try {
						start.await();
						noLockCouponIssuer.issue(couponId, memberId, NOW);
						issued.incrementAndGet();
					} catch (CouponException e) {
						// 소진 판정에 걸린 요청. 늦게 온 쪽은 여전히 정상적으로 거절된다.
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (RuntimeException e) {
						// 락이 없으면 같은 행을 동시에 쓰다 낙관적/무결성 충돌이 날 수도 있다. 여기선 셈에서 뺀다.
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(2, TimeUnit.MINUTES)).isTrue();
		} finally {
			executor.shutdownNow();
		}

		long actuallyIssued = couponIssueRepository.countByCouponId(couponId);
		int counter = couponRepository.findById(couponId).orElseThrow().getIssuedQuantity();

		System.out.printf("[대비군] 재고 %d, 시도 %d → 발급 성공 %d건, 실제 행 %d건, 카운터 %d%n",
			STOCK, ATTEMPTS, issued.get(), actuallyIssued, counter);

		assertThat(actuallyIssued)
			.as("락이 없으면 재고를 넘겨 발급된다. 100장에서 멈췄다면 경합이 재현되지 않은 것이니 조건을 의심해야 한다")
			.isGreaterThan(STOCK);
	}
}
