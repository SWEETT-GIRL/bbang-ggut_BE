package sweetgirl.croissant.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.issuer.CouponIssuer;
import sweetgirl.croissant.coupon.issuer.PessimisticCouponIssuer;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * 랩 PG 에서 비관적 락이 선착순 발급의 정합성을 지키는지 본다 (설계 §1 Q2).
 *
 * <p><b>H2 로는 검증할 수 없다.</b> {@code SELECT ... FOR UPDATE} 의 락 대기·타임아웃 동작이 PostgreSQL 과 다르다.
 * 통과해도 의미가 없으므로 랩으로 분리했다.
 *
 * <p>테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}). 워커 스레드는 저마다 별도 트랜잭션에서 <b>실제로 커밋</b>해야
 * 경합이 재현되고, 테스트가 그 결과를 읽을 수 있다. 롤백이 없으므로 뒷정리는 {@code @AfterEach} 가 직접 한다.
 *
 * <p>실행: 랩 PG(docker-compose.lab-postgres.yml)를 띄운 뒤 {@code ./gradlew test -PlabTests}.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, PessimisticCouponIssuer.class})
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
class CouponConcurrencyLabTest {

	private static final int STOCK = 100;
	private static final int ATTEMPTS = 1000;
	private static final int THREADS = 64;
	private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 9, 21, 0, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 12, 0);

	@Autowired
	private CouponIssuer couponIssuer;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private CouponIssueRepository couponIssueRepository;
	@Autowired
	private TransactionTemplate transactionTemplate;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long couponId;

	@BeforeEach
	void createCoupon() {
		couponId = couponRepository.save(Coupon.open("랩 선착순 쿠폰", STOCK, OPENED_AT)).getId();
	}

	/** 테스트 트랜잭션이 없어 워커가 커밋한 행이 그대로 남는다 — 직접 지운다. */
	@AfterEach
	void deleteCoupon() {
		jdbcTemplate.update("DELETE FROM coupon_issues WHERE coupon_id = ?", couponId);
		jdbcTemplate.update("DELETE FROM coupons WHERE id = ?", couponId);
	}

	private List<Long> distinctMemberIds(int count) {
		List<Long> ids = jdbcTemplate.queryForList(
			"SELECT id FROM member ORDER BY id LIMIT ?", Long.class, count);
		assertThat(ids).as("랩 DB 회원이 %d명 이상이어야 한다", count).hasSize(count);
		return ids;
	}

	/**
	 * {@code attempts} 건을 한꺼번에 출발시켜 발급을 시도한다.
	 *
	 * <p>출발 신호({@code start})로 스레드를 모아 뒀다가 동시에 푼다 — 순서대로 부르면 경합이 안 생겨
	 * 락이 있든 없든 통과해 버린다.
	 */
	private Outcome issueConcurrently(int attempts, IntFunction<Long> memberIdAt) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(attempts);
		Outcome outcome = new Outcome();

		try {
			for (int i = 0; i < attempts; i++) {
				Long memberId = memberIdAt.apply(i);
				executor.submit(() -> {
					try {
						start.await();
						couponIssuer.issue(couponId, memberId, NOW);
						outcome.issued.incrementAndGet();
					} catch (CouponException e) {
						outcome.count(e);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (RuntimeException e) {
						// 도메인 예외로 번역되지 않은 실패. 삼키면 "기대한 예외가 안 왔다"로만 보여 원인을 못 찾는다.
						outcome.untranslated.add(e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(2, TimeUnit.MINUTES)).as("2분 안에 모든 시도가 끝나야 한다").isTrue();
		} finally {
			executor.shutdownNow();
		}
		return outcome;
	}

	@Test
	@DisplayName("비관적 락: 재고 100장에 1000명이 동시에 몰려도 정확히 100장만 나간다 — 카운터와 실제 발급 행 수도 일치한다")
	void pessimisticLock_issuesExactlyStock() throws InterruptedException {
		List<Long> memberIds = distinctMemberIds(ATTEMPTS);

		Outcome outcome = issueConcurrently(ATTEMPTS, memberIds::get);

		outcome.assertAllTranslated();
		assertThat(outcome.issued).hasValue(STOCK);
		assertThat(outcome.soldOut).hasValue(ATTEMPTS - STOCK);
		assertThat(outcome.alreadyIssued).hasValue(0);
		assertThat(outcome.busy).as("락 대기 타임아웃 없이 다 처리돼야 한다").hasValue(0);

		assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(STOCK);
		assertThat(couponRepository.findById(couponId).orElseThrow().getIssuedQuantity()).isEqualTo(STOCK);
	}

	@Test
	@DisplayName("비관적 락: 같은 회원이 100번 동시에 눌러도 1장만 받는다 — 나머지는 이미 발급됨")
	void pessimisticLock_sameMemberGetsOnlyOne() throws InterruptedException {
		Long memberId = distinctMemberIds(1).get(0);

		Outcome outcome = issueConcurrently(100, index -> memberId);

		outcome.assertAllTranslated();
		assertThat(outcome.issued).hasValue(1);
		assertThat(outcome.alreadyIssued).hasValue(99);
		assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(1);
		assertThat(couponRepository.findById(couponId).orElseThrow().getIssuedQuantity())
			.as("중복 시도는 롤백되므로 카운터도 1이어야 한다")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("비관적 락: 소진된 뒤 들어온 요청은 소진(409)으로 떨어진다")
	void pessimisticLock_afterSoldOut_rejects() throws InterruptedException {
		List<Long> memberIds = distinctMemberIds(STOCK + 1);
		issueConcurrently(STOCK, memberIds::get);

		Long latecomer = memberIds.get(STOCK);

		assertThatThrownBy(() -> couponIssuer.issue(couponId, latecomer, NOW))
			.isInstanceOf(CouponException.class)
			.extracting(exception -> ((CouponException)exception).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_SOLD_OUT);
	}

	/**
	 * 락 타임아웃이 <b>실제로</b> 동작하는지 본다.
	 *
	 * <p>PostgreSQL 에는 오라클의 {@code FOR UPDATE WAIT n} 같은 구문이 없다. 그래서
	 * {@code jakarta.persistence.lock.timeout} 힌트가 조용히 무시되고 무한 대기할 수 있다.
	 * 무시된다면 여기서 타임아웃으로 드러난다 — 그때는 {@code SET LOCAL lock_timeout} 으로 바꿔야 한다.
	 */
	@Test
	@Timeout(30)
	@DisplayName("비관적 락: 앞선 트랜잭션이 락을 쥐고 있으면 뒤따르는 발급은 무한정 기다리지 않고 붐빔(503)으로 끊긴다")
	void pessimisticLock_whenLockHeld_timesOut() throws Exception {
		Long memberId = distinctMemberIds(1).get(0);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();

		Future<?> lockHolder = executor.submit(() -> transactionTemplate.execute(status -> {
			couponRepository.findWithLockById(couponId);
			locked.countDown();
			try {
				release.await(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}));

		try {
			assertThat(locked.await(10, TimeUnit.SECONDS)).as("선행 트랜잭션이 락을 잡아야 한다").isTrue();

			assertThatThrownBy(() -> couponIssuer.issue(couponId, memberId, NOW))
				.isInstanceOf(CouponException.class)
				.extracting(exception -> ((CouponException)exception).getErrorCode())
				.isEqualTo(CouponErrorCode.COUPON_ISSUE_BUSY);
		} finally {
			release.countDown();
			lockHolder.get(10, TimeUnit.SECONDS);
			executor.shutdownNow();
		}
	}

	/** 시도 결과를 종류별로 센다. 합이 시도 수와 맞아야 빠뜨린 경로가 없다. */
	private static final class Outcome {
		private final AtomicInteger issued = new AtomicInteger();
		private final AtomicInteger soldOut = new AtomicInteger();
		private final AtomicInteger alreadyIssued = new AtomicInteger();
		private final AtomicInteger busy = new AtomicInteger();
		private final Queue<RuntimeException> untranslated = new ConcurrentLinkedQueue<>();

		/** 도메인 예외로 번역되지 않은 실패가 있으면 그 예외를 그대로 드러낸다 — 원인이 보여야 고칠 수 있다. */
		private void assertAllTranslated() {
			assertThat(untranslated).as("도메인 예외로 번역되지 않은 실패 %d건", untranslated.size()).isEmpty();
		}

		private void count(CouponException exception) {
			if (exception.getErrorCode() == CouponErrorCode.COUPON_SOLD_OUT) {
				soldOut.incrementAndGet();
			} else if (exception.getErrorCode() == CouponErrorCode.COUPON_ALREADY_ISSUED) {
				alreadyIssued.incrementAndGet();
			} else if (exception.getErrorCode() == CouponErrorCode.COUPON_ISSUE_BUSY) {
				busy.incrementAndGet();
			}
		}
	}
}
