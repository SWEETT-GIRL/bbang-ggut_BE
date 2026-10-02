package sweetgirl.croissant.menu.entity;

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
 * 가게 메뉴 가격의 공동 수정 이력 — 누가·언제·이전·새 값. 빵 평가 화면의 가격 수정만 남긴다(어드민·제보 승인은 기존대로).
 * 연관 관계를 잡지 않은 이유는 MemberWorld 와 같다 — id 만 있으면 된다.
 */
@Entity
@Table(name = "menu_price_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuPriceHistory extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long menuId;

	@Column(nullable = false)
	private Long memberId;

	private Integer oldPrice;

	@Column(nullable = false)
	private int newPrice;

	private MenuPriceHistory(Long menuId, Long memberId, Integer oldPrice, int newPrice) {
		this.menuId = menuId;
		this.memberId = memberId;
		this.oldPrice = oldPrice;
		this.newPrice = newPrice;
	}

	public static MenuPriceHistory of(Long menuId, Long memberId, Integer oldPrice, int newPrice) {
		return new MenuPriceHistory(menuId, memberId, oldPrice, newPrice);
	}
}
