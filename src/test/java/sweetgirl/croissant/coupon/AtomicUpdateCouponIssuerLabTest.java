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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.issuer.AtomicUpdateCouponIssuer;
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * 원자적 조건부 UPDATE 가 비관적 락과 같은 정합성을 내는지 본다 (v2).
 *
 * <p>락을 빼고 조건을 SQL 안으로 옮겼으니, "정확히 재고만큼"이 여전히 성립하는지가 첫 질문이다.
 * 품절 플래그는 성능 장치라서 <b>켜든 끄든 결과가 같아야 한다</b> — 그것도 여기서 확인한다.
 * 플래그를 켠 쪽만 정확하다면 그건 플래그가 정합성을 떠받치고 있다는 뜻이고, 설계가 틀린 것이다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, AtomicUpdateCouponIssuer.class, CouponSoldOutFlags.class})
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
	"spring.jpa.show-sql=false",
	// @Import 는 @ConditionalOnProperty 를 건너뛰지 않는다 — 조건을 만족시켜야 빈이 등록된다.
	"coupon.issuer=atomic"
})
class AtomicUpdateCouponIssuerLabTest {

	private static final int STOCK = 100;
	private static final int ATTEMPTS = 1000;
	private static final int THREADS = 64;
	private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 9, 21, 0, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 12, 0);

	@Autowired
	private AtomicUpdateCouponIssuer couponIssuer;
	@Autowired
	private CouponSoldOutFlags soldOutFlags;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private CouponIssueRepository couponIssueRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long couponId;

	@BeforeEach
	void createCoupon() {
		couponId = couponRepository.save(Coupon.open("랩 원자적 UPDATE 쿠폰", STOCK, OPENED_AT)).getId();
	}

	@AfterEach
	void deleteCoupon() {
		soldOutFlags.clear(couponId);
		jdbcTemplate.update("DELETE FROM coupon_issues WHERE coupon_id = ?", couponId);
		jdbcTemplate.update("DELETE FROM coupons WHERE id = ?", couponId);
	}

	private List<Long> distinctMemberIds(int count) {
		List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM member ORDER BY id LIMIT ?", Long.class, count);
		assertThat(ids).hasSize(count);
		return ids;
	}

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
						outcome.untranslated.add(e);
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
		return outcome;
	}

	private void assertExactlyStockIssued(Outcome outcome) {
		outcome.assertAllTranslated();
		assertThat(outcome.issued).hasValue(STOCK);
		assertThat(outcome.alreadyIssued).hasValue(0);
		assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(STOCK);
		assertThat(couponRepository.findById(couponId).orElseThrow().getIssuedQuantity()).isEqualTo(STOCK);
	}

	@Test
	@DisplayName("원자적 UPDATE: 재고 100장에 1000명이 동시에 몰려도 정확히 100장만 나간다 — 락 없이도 정합성이 지켜진다")
	void atomicUpdate_issuesExactlyStock() throws InterruptedException {
		List<Long> memberIds = distinctMemberIds(ATTEMPTS);

		Outcome outcome = issueConcurrently(ATTEMPTS, memberIds::get);

		assertExactlyStockIssued(outcome);
		assertThat(outcome.soldOut).hasValue(ATTEMPTS - STOCK);
	}

	@Test
	@DisplayName("원자적 UPDATE: 같은 회원이 100번 동시에 눌러도 1장만 받는다 — 중복은 유니크 제약이 잡고 카운터도 롤백된다")
	void atomicUpdate_sameMemberGetsOnlyOne() throws InterruptedException {
		Long memberId = distinctMemberIds(1).get(0);

		Outcome outcome = issueConcurrently(100, index -> memberId);

		outcome.assertAllTranslated();
		assertThat(outcome.issued).hasValue(1);
		assertThat(outcome.alreadyIssued).hasValue(99);
		assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(1);
		assertThat(couponRepository.findById(couponId).orElseThrow().getIssuedQuantity()).isEqualTo(1);
	}

	@Test
	@DisplayName("원자적 UPDATE: 아직 열리지 않은 쿠폰은 발급되지 않는다 — 조건이 WHERE 안에 있어 카운터도 안 오른다")
	void atomicUpdate_beforeOpen_rejects() {
		Long memberId = distinctMemberIds(1).get(0);
		LocalDateTime beforeOpen = OPENED_AT.minusHours(1);

		assertThatThrownBy(() -> couponIssuer.issue(couponId, memberId, beforeOpen))
			.isInstanceOf(CouponException.class)
			.extracting(exception -> ((CouponException)exception).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_NOT_OPENED);
		assertThat(couponRepository.findById(couponId).orElseThrow().getIssuedQuantity()).isZero();
	}

	/**
	 * 플래그는 성능 장치다. 켜고 끈 결과가 다르면 정합성이 플래그에 의존하고 있다는 뜻이라 설계가 틀린 것이다.
	 * 기본값은 꺼짐이라, 켠 경우는 별도 컨텍스트로 돌린다.
	 */
	@Nested
	@DisplayName("품절 플래그를 켜도")
	@TestPropertySource(properties = "coupon.sold-out-flag.enabled=true")
	class WithSoldOutFlag {

		@Test
		@DisplayName("결과가 똑같이 정확히 100장이다 — 플래그는 정합성에 관여하지 않는다")
		void withFlag_issuesExactlyStock() throws InterruptedException {
			assertThat(soldOutFlags.isEnabled()).isTrue();
			List<Long> memberIds = distinctMemberIds(ATTEMPTS);

			Outcome outcome = issueConcurrently(ATTEMPTS, memberIds::get);

			assertExactlyStockIssued(outcome);
			assertThat(soldOutFlags.isSoldOut(couponId))
				.as("소진이 확인됐으니 플래그가 켜져 있어야 한다")
				.isTrue();
		}

		/**
		 * 재고보다 <b>한 건 더</b> 시도한다. 플래그는 DB 가 소진을 확인해 줘야 켜지므로,
		 * 재고만큼만 넣으면 전부 성공해서 아무도 거절당하지 않고 플래그도 안 켜진다.
		 * (미리 짐작해서 켜지 않는 설계라 그렇다 — 재고가 남았는데 막는 쪽으로는 틀리지 않는다.)
		 */
		@Test
		@DisplayName("재고를 되돌리면 플래그도 꺼져 다시 발급된다 — 끄는 경로를 빠뜨리면 영영 품절로 남는다")
		void clearingFlag_allowsIssuingAgain() throws InterruptedException {
			List<Long> memberIds = distinctMemberIds(STOCK + 2);
			issueConcurrently(STOCK + 1, memberIds::get);
			assertThat(soldOutFlags.isSoldOut(couponId))
				.as("한 명이 거절당했으니 플래그가 켜져 있어야 한다")
				.isTrue();

			jdbcTemplate.update("DELETE FROM coupon_issues WHERE coupon_id = ?", couponId);
			jdbcTemplate.update("UPDATE coupons SET issued_quantity = 0 WHERE id = ?", couponId);
			soldOutFlags.clear(couponId);

			assertThat(couponIssuer.issue(couponId, memberIds.get(STOCK + 1), NOW)).isNotNull();
		}
	}

	private static final class Outcome {
		private final AtomicInteger issued = new AtomicInteger();
		private final AtomicInteger soldOut = new AtomicInteger();
		private final AtomicInteger alreadyIssued = new AtomicInteger();
		private final Queue<RuntimeException> untranslated = new ConcurrentLinkedQueue<>();

		private void assertAllTranslated() {
			assertThat(untranslated).as("도메인 예외로 번역되지 않은 실패 %d건", untranslated.size()).isEmpty();
		}

		private void count(CouponException exception) {
			if (exception.getErrorCode() == CouponErrorCode.COUPON_SOLD_OUT) {
				soldOut.incrementAndGet();
			} else if (exception.getErrorCode() == CouponErrorCode.COUPON_ALREADY_ISSUED) {
				alreadyIssued.incrementAndGet();
			}
		}
	}
}
