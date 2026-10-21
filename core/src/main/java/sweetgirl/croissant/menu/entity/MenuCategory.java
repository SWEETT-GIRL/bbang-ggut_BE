package sweetgirl.croissant.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.menu.enums.MenuKind;

@Entity
@Table(name = "menu_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MenuKind name;

	/**
	 * 빵BTI 4축의 극값 (A극 −1 / B극 +1). 매핑표(검수 확정본)가 채우기 전까지 NULL 이고, NULL 은 "근거 없음"이다.
	 * 가게·사용자 축(TasteAxis)은 이 값의 가중 평균이다.
	 */
	@Column(name = "axis_taste")
	private Short axisTaste;

	@Column(name = "axis_texture")
	private Short axisTexture;

	@Column(name = "axis_filling")
	private Short axisFilling;

	@Column(name = "axis_vibe")
	private Short axisVibe;

	@Builder
	public MenuCategory(MenuKind name) {
		this.name = name;
	}

	/** 네 축이 모두 있어야 축 계산에 넣는다. */
	public boolean hasAxis() {
		return axisTaste != null && axisTexture != null && axisFilling != null && axisVibe != null;
	}
}
