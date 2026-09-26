package sweetgirl.croissant.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.coupon.entity.Coupon;
import sweetgirl.croissant.coupon.entity.CouponIssue;
import sweetgirl.croissant.coupon.repository.CouponIssueRepository;
import sweetgirl.croissant.coupon.repository.CouponRepository;

/**
 * 랩 PG(운영 덤프 복제본)에서 쿠폰 스키마를 검증한다.
 *
 * <p>하는 일 둘: (1) Flyway 로 V20260921020149 를 적용하고 Hibernate validate 가 엔티티와 실제 컬럼을 맞춰 보는지,
 * (2) 제약이 의도대로 걸렸는지 — 유니크(1인 1매)는 막고, 재고 초과는 <b>막지 않는</b>다.
 *
 * <p>재고 초과를 막지 않는 것이 이 브랜치의 전제다. DB 가 먼저 막으면 락 없는 구현에서 초과 발급이 재현되지 않아
 * "락이 일하고 있다"를 보일 수 없다. 그 전제가 조용히 뒤집히지 않도록 여기서 못박는다.
 *
 * <p>실행: 랩 PG(docker-compose.lab-postgres.yml)를 띄운 뒤 {@code ./gradlew test -PlabTests}
 * (기본 빌드·CI 에서는 'lab' 태그가 제외된다). 접속은 시스템 프로퍼티 lab.db.url / lab.db.user / lab.db.password 로 바꿀 수 있다.
 * 개발·운영 DB 터널(15433 · 15432)을 가리키게 하지 않는다 — Flyway 가 실제로 마이그레이션을 적용한다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
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
class CouponSchemaLabTest {

	private static final LocalDateTime OPENS_AT = LocalDateTime.of(2026, 9, 21, 12, 0);

	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private CouponIssueRepository couponIssueRepository;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long anyMemberId() {
		return jdbcTemplate.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
	}

	private Long anotherMemberId(Long exceptId) {
		return jdbcTemplate.queryForObject("SELECT id FROM member WHERE id <> ? ORDER BY id LIMIT 1",
			Long.class, exceptId);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private Coupon savedCoupon(int totalQuantity) {
		return couponRepository.save(Coupon.open("랩 선착순 쿠폰", totalQuantity, OPENS_AT));
	}

	@Test
	@DisplayName("coupons: 저장한 값이 그대로 돌아오고 발급 수는 0에서 시작한다 — created_at 은 감사(auditing)가 채운다")
	void coupon_roundTrip() {
		Long id = savedCoupon(100).getId();
		flushAndClear();

		Coupon found = couponRepository.findById(id).orElseThrow();

		assertThat(found.getName()).isEqualTo("랩 선착순 쿠폰");
		assertThat(found.getTotalQuantity()).isEqualTo(100);
		assertThat(found.getIssuedQuantity()).isZero();
		assertThat(found.getOpensAt()).isEqualTo(OPENS_AT);
		assertThat(found.remainingQuantity()).isEqualTo(100);
		assertThat(found.isSoldOut()).isFalse();
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("coupon_issues: 발급 한 건이 그대로 돌아온다 — coupon_id·member_id 는 연관관계가 아니라 식별자다")
	void couponIssue_roundTrip() {
		Long couponId = savedCoupon(100).getId();
		Long memberId = anyMemberId();
		LocalDateTime issuedAt = LocalDateTime.of(2026, 9, 21, 12, 0, 1);

		Long id = couponIssueRepository.save(CouponIssue.of(couponId, memberId, issuedAt)).getId();
		flushAndClear();

		CouponIssue found = couponIssueRepository.findById(id).orElseThrow();

		assertThat(found.getCouponId()).isEqualTo(couponId);
		assertThat(found.getMemberId()).isEqualTo(memberId);
		assertThat(found.getIssuedAt()).isEqualTo(issuedAt);
		assertThat(couponIssueRepository.existsByCouponIdAndMemberId(couponId, memberId)).isTrue();
		assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(1);
	}

	/**
	 * IDENTITY 전략이라 {@code save()} 가 곧바로 INSERT 를 보낸다 — 유니크 위반은 flush 가 아니라 save 에서 터진다.
	 * 발급 경로에서도 마찬가지다: 중복 발급은 트랜잭션 끝이 아니라 INSERT 하는 그 자리에서 잡힌다.
	 */
	@Test
	@DisplayName("coupon_issues: 같은 쿠폰을 같은 회원이 두 번 받으면 유니크 제약이 막는다 — 1인 1매의 최후 방어선")
	void couponIssue_duplicateForSameMember_isRejected() {
		Long couponId = savedCoupon(100).getId();
		Long memberId = anyMemberId();
		couponIssueRepository.save(CouponIssue.of(couponId, memberId, LocalDateTime.now()));
		CouponIssue duplicate = CouponIssue.of(couponId, memberId, LocalDateTime.now());

		assertThatThrownBy(() -> couponIssueRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("coupon_issues: 다른 회원이면 같은 쿠폰을 받을 수 있다 — 유니크는 (coupon_id, member_id) 쌍이다")
	void couponIssue_differentMembers_areAllowed() {
		Long couponId = savedCoupon(100).getId();
		Long memberId = anyMemberId();
		couponIssueRepository.save(CouponIssue.of(couponId, memberId, LocalDateTime.now()));
		couponIssueRepository.save(CouponIssue.of(couponId, anotherMemberId(memberId), LocalDateTime.now()));
		flushAndClear();

		assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(2);
	}

	@Test
	@DisplayName("coupon_issues: 없는 회원으로는 발급되지 않는다 — member FK 가 막는다")
	void couponIssue_unknownMember_isRejected() {
		Long couponId = savedCoupon(100).getId();
		flushAndClear();

		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO coupon_issues (coupon_id, member_id, issued_at) VALUES (?, ?, now())",
			couponId, Long.MAX_VALUE))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("coupons: 재고를 넘겨도 DB 가 막지 않는다 — 락 없이 초과 발급이 나는 것을 재현해야 하므로 CHECK 를 일부러 걸지 않았다")
	void coupon_issuedQuantityMayExceedTotal_byDesign() {
		Coupon coupon = savedCoupon(1);
		coupon.issueOne();
		coupon.issueOne();
		coupon.issueOne();
		flushAndClear();

		Coupon found = couponRepository.findById(coupon.getId()).orElseThrow();

		assertThat(found.getIssuedQuantity()).isEqualTo(3);
		assertThat(found.getTotalQuantity()).isEqualTo(1);
		assertThat(found.isSoldOut()).isTrue();
		assertThat(found.remainingQuantity()).isZero();
	}
}
