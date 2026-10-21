package sweetgirl.croissant.dev.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

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
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;
import sweetgirl.croissant.dev.dto.response.DevCouponResetResponse;

/**
 * 측정용 쿠폰 초기화가 실제 PostgreSQL 에서 도는지 본다.
 *
 * <p>네이티브 SQL 세 개(조회·INSERT RETURNING·UPDATE)라 문법과 동작을 실 DB 에서만 확인할 수 있다.
 * 측정 회차 사이에 이게 조용히 실패하면 두 번째 회차부터 전부 소진 응답만 나와 결과가 통째로 망가진다.
 *
 * <p>{@code @Profile("local")} 빈이지만 프로파일을 켜지 않는다 — local 프로파일은 랩이 아닌 DB 를
 * 가리킨다. 빈 등록 없이 직접 생성해서 랩 PG 에 붙인 {@code JdbcTemplate} 만 넘긴다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
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
class DevCouponServiceLabTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private DevCouponService devCouponService;

	@BeforeEach
	void setUp() {
		// 플래그는 꺼진 채로 만든다 — 이 테스트가 보는 건 초기화 SQL 이지 플래그 동작이 아니다.
		devCouponService = new DevCouponService(jdbcTemplate, new CouponSoldOutFlags(false));
		removeLoadTestCoupon();
	}

	@AfterEach
	void tearDown() {
		removeLoadTestCoupon();
	}

	private void removeLoadTestCoupon() {
		jdbcTemplate.update("""
				DELETE FROM coupon_issues WHERE coupon_id IN (SELECT id FROM coupons WHERE name = ?)
				""", DevCouponService.LOAD_TEST_COUPON_NAME);
		jdbcTemplate.update("DELETE FROM coupons WHERE name = ?", DevCouponService.LOAD_TEST_COUPON_NAME);
	}

	private Long anyMemberId() {
		return jdbcTemplate.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
	}

	@Test
	@DisplayName("측정용 쿠폰이 없으면 만들어서 id 를 돌려준다 — 첫 회차")
	void reset_createsWhenMissing() {
		DevCouponResetResponse response = devCouponService.reset(100);

		assertThat(response.couponId()).isNotNull();
		assertThat(response.totalQuantity()).isEqualTo(100);
		assertThat(response.deletedIssues()).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT issued_quantity FROM coupons WHERE id = ?", Integer.class, response.couponId())).isZero();
	}

	@Test
	@DisplayName("두 번 불러도 쿠폰을 새로 만들지 않고 같은 id 를 준다 — 회차마다 행이 쌓이면 안 된다")
	void reset_reusesSameCoupon() {
		Long first = devCouponService.reset(100).couponId();
		Long second = devCouponService.reset(100).couponId();

		assertThat(second).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM coupons WHERE name = ?", Integer.class,
			DevCouponService.LOAD_TEST_COUPON_NAME)).isEqualTo(1);
	}

	@Test
	@DisplayName("직전 회차의 발급 내역을 지우고 재고를 되돌린다 — 지운 건수도 알려준다")
	void reset_clearsPreviousRun() {
		Long couponId = devCouponService.reset(100).couponId();
		jdbcTemplate.update("""
				INSERT INTO coupon_issues (coupon_id, member_id, issued_at) VALUES (?, ?, ?)
				""", couponId, anyMemberId(), LocalDateTime.now());
		jdbcTemplate.update("UPDATE coupons SET issued_quantity = 100 WHERE id = ?", couponId);

		DevCouponResetResponse response = devCouponService.reset(250);

		assertThat(response.couponId()).isEqualTo(couponId);
		assertThat(response.deletedIssues()).isEqualTo(1);
		assertThat(response.totalQuantity()).isEqualTo(250);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT issued_quantity FROM coupons WHERE id = ?", Integer.class, couponId)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM coupon_issues WHERE coupon_id = ?", Integer.class, couponId)).isZero();
	}

	@Test
	@DisplayName("초기화 직후에는 바로 발급받을 수 있는 상태다 — opens_at 이 과거로 당겨진다")
	void reset_opensImmediately() {
		Long couponId = devCouponService.reset(100).couponId();

		LocalDateTime opensAt = jdbcTemplate.queryForObject(
			"SELECT opens_at FROM coupons WHERE id = ?", LocalDateTime.class, couponId);

		assertThat(opensAt).isBeforeOrEqualTo(LocalDateTime.now());
	}
}
