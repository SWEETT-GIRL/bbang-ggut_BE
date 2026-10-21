package sweetgirl.croissant.coupon.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발급된 쿠폰 한 장. 한 번 쌓이면 바뀌지 않는다.
 *
 * <p>{@code couponId} · {@code memberId} 를 연관관계가 아니라 식별자로 들고 있다 —
 * 발급은 쿠폰 행에 락을 쥔 채 일어나고, 그 안에서 {@code Member} 를 로드하면 왕복이 하나 더 붙는다.
 * 락 보유 시간이 이 측정의 핵심 변수라 쓰는 쿼리를 최소로 둔다. 발급 경로에서 회원 정보를 읽을 일도 없다.
 *
 * <p>{@code BaseTimeEntity} 를 상속하지 않는다 — {@code issuedAt} 이 곧 생성 시각이고, 수정되지 않는다.
 */
@Entity
@Table(name = "coupon_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long couponId;

	@Column(nullable = false)
	private Long memberId;

	@Column(nullable = false)
	private LocalDateTime issuedAt;

	private CouponIssue(Long couponId, Long memberId, LocalDateTime issuedAt) {
		this.couponId = couponId;
		this.memberId = memberId;
		this.issuedAt = issuedAt;
	}

	public static CouponIssue of(Long couponId, Long memberId, LocalDateTime issuedAt) {
		return new CouponIssue(couponId, memberId, issuedAt);
	}
}
