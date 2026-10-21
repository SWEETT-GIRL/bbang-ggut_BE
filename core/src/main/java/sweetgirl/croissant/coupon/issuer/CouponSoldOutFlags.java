package sweetgirl.croissant.coupon.issuer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 소진된 쿠폰을 기억해 두는 인메모리 플래그. <b>성능 장치이지 정합성 장치가 아니다.</b>
 *
 * <p>정합성은 {@code UPDATE ... WHERE issued_quantity < total_quantity} 한 문장과 {@code uq_coupon_issue}
 * 유니크 제약이 지킨다. 이 플래그가 없어도, 늦게 켜져도, 서버마다 따로 켜져도 <b>발급 수량은 정확하다.</b>
 * 있으면 답이 뻔한 요청이 DB 까지 가지 않아 커넥션을 아낄 뿐이다.
 *
 * <p><b>한 방향으로만 틀리게 만든다.</b> 덜 막으면 DB 까지 가서 정확히 거절되므로 느릴 뿐 안전하지만,
 * 과하게 막으면 재고가 남았는데 거절하게 되고 그건 되돌릴 수 없다. 그래서 플래그는
 * <b>DB 가 소진을 확인해 준 뒤에만</b> 켠다 — 미리 짐작해서 켜지 않는다.
 *
 * <p>인스턴스 로컬이라 서버가 여러 대면 각자 따로 켜진다. 공유하지 않아도 되는 이유가 위와 같다 —
 * 아직 안 켜진 인스턴스의 요청은 그냥 DB 까지 가고, 거기서 정확히 판정된다. 그래서 Redis 가 필요 없다.
 *
 * <p>측정에서 기여도를 분리하려고 {@code coupon.sold-out-flag.enabled} 로 켜고 끈다(기본 꺼짐).
 */
@Component
public class CouponSoldOutFlags {

	private final boolean enabled;
	private final Map<Long, AtomicBoolean> soldOutByCouponId = new ConcurrentHashMap<>();

	public CouponSoldOutFlags(@Value("${coupon.sold-out-flag.enabled:false}") boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isSoldOut(Long couponId) {
		if (!enabled) {
			return false;
		}
		AtomicBoolean soldOut = soldOutByCouponId.get(couponId);
		return soldOut != null && soldOut.get();
	}

	/** DB 가 소진을 확인해 준 뒤에만 부른다. */
	public void markSoldOut(Long couponId) {
		if (!enabled) {
			return;
		}
		soldOutByCouponId.computeIfAbsent(couponId, id -> new AtomicBoolean()).set(true);
	}

	/**
	 * 재고를 다시 채웠을 때 부른다. 빠뜨리면 그 쿠폰은 영영 품절로 남는다 —
	 * 측정을 반복할 때 2회차부터 전부 품절만 나오는 식으로 드러난다.
	 *
	 * <p>꺼져 있을 때도 지운다. 껐다 켜는 사이에 남은 값이 살아나지 않게.
	 */
	public void clear(Long couponId) {
		soldOutByCouponId.remove(couponId);
	}
}
