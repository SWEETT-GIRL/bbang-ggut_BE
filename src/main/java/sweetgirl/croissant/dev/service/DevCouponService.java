package sweetgirl.croissant.dev.service;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevCouponResetResponse;

/**
 * 부하 측정을 여러 번 돌리기 위한 쿠폰 초기화. <b>local 프로파일에서만 등록된다.</b>
 *
 * <p>한 번 측정하면 재고가 소진되고 발급 내역이 쌓여, 다음 회차는 전부 소진 응답만 받는다.
 * 회차마다 손으로 DB 를 지우면 측정 사이에 사람 손이 끼어 조건이 흔들리므로 엔드포인트로 둔다.
 *
 * <p>도메인 엔티티에 초기화 메서드를 만들지 않고 네이티브 SQL 로 처리한다 — 측정 도구 때문에
 * {@code Coupon} 에 "재고를 되돌린다" 같은, 서비스에 존재하지 않는 행위를 심고 싶지 않다.
 */
@Profile("local")
@Service
@RequiredArgsConstructor
public class DevCouponService {

	/** 측정용 쿠폰은 이 이름 하나를 재사용한다 — 회차마다 새로 만들면 행만 쌓인다. */
	static final String LOAD_TEST_COUPON_NAME = "[부하측정] 선착순 쿠폰";

	private final JdbcTemplate jdbcTemplate;

	@Transactional
	public DevCouponResetResponse reset(int totalQuantity) {
		LocalDateTime now = LocalDateTime.now();
		Long couponId = jdbcTemplate.query(
			"SELECT id FROM coupons WHERE name = ? ORDER BY id LIMIT 1",
			rs -> rs.next() ? rs.getLong(1) : null,
			LOAD_TEST_COUPON_NAME);

		if (couponId == null) {
			couponId = jdbcTemplate.queryForObject("""
					INSERT INTO coupons (name, total_quantity, issued_quantity, opens_at, created_at, updated_at)
					VALUES (?, ?, 0, ?, ?, ?) RETURNING id
					""", Long.class, LOAD_TEST_COUPON_NAME, totalQuantity, now, now, now);
			return new DevCouponResetResponse(couponId, totalQuantity, 0);
		}

		int deleted = jdbcTemplate.update("DELETE FROM coupon_issues WHERE coupon_id = ?", couponId);
		jdbcTemplate.update("""
				UPDATE coupons SET total_quantity = ?, issued_quantity = 0, opens_at = ?, updated_at = ?
				WHERE id = ?
				""", totalQuantity, now, now, couponId);
		return new DevCouponResetResponse(couponId, totalQuantity, deleted);
	}
}
