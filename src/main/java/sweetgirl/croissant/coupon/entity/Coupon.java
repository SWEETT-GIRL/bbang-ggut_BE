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
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/**
 * 선착순 쿠폰. {@code issuedQuantity} 가 모든 발급 요청이 줄 서는 단 하나의 지점이다.
 *
 * <p>동시성 측정용 최소 모델이다 — 사용·만료·취소는 없다.
 */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false)
	private int totalQuantity;

	@Column(nullable = false)
	private int issuedQuantity;

	@Column(nullable = false)
	private LocalDateTime opensAt;

	private Coupon(String name, int totalQuantity, LocalDateTime opensAt) {
		this.name = name;
		this.totalQuantity = totalQuantity;
		this.issuedQuantity = 0;
		this.opensAt = opensAt;
	}

	public static Coupon open(String name, int totalQuantity, LocalDateTime opensAt) {
		return new Coupon(name, totalQuantity, opensAt);
	}

	public boolean isSoldOut() {
		return issuedQuantity >= totalQuantity;
	}

	public boolean isNotOpenedAt(LocalDateTime now) {
		return now.isBefore(opensAt);
	}

	public int remainingQuantity() {
		return Math.max(totalQuantity - issuedQuantity, 0);
	}

	/**
	 * 재고를 한 장 줄인다.
	 *
	 * <p><b>일부러 재고를 검사하지 않는다.</b> 검사해서 막으면 락 없는 구현에서 초과 발급이 재현되지 않아,
	 * 락이 실제로 일하고 있다는 것을 보일 수 없다. 소진 판정은 부르는 쪽(Service)의 책임이다.
	 */
	public void issueOne() {
		this.issuedQuantity++;
	}
}
