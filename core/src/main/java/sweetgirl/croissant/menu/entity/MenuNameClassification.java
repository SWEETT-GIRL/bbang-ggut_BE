package sweetgirl.croissant.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.enums.MenuKind;

/**
 * 메뉴 이름(정규화 키) 단위 AI 분류 결과 캐시.
 * 같은 이름은 AI 를 다시 부르지 않고 같은 답을 받는다 — 비용보다 일관성 때문이다.
 * kind 가 null 이면 3회 답이 갈렸거나 '빵'·'기타'로만 답한 것 — 사람이 볼 것이고, 같은 이름은 다시 묻지 않는다.
 */
@Entity
@Table(name = "menu_name_classification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuNameClassification extends BaseTimeEntity {

	@Id
	@Column(length = 100)
	private String normalizedName;

	@Enumerated(EnumType.STRING)
	@Column(length = 50)
	private MenuKind kind;

	@Column(name = "is_bakery_item", nullable = false)
	private boolean bakeryItem;

	/** 모델 자기보고 신뢰도 — 판단엔 안 쓰고 저장만. 0.99 를 달고도 답이 갈리는 걸 봤다 */
	private Float confidence;

	@Column(nullable = false)
	private boolean agreed;

	@Column(nullable = false, length = 50)
	private String model;

	private MenuNameClassification(
			String normalizedName,
			MenuKind kind,
			boolean bakeryItem,
			Float confidence,
			boolean agreed,
			String model) {
		this.normalizedName = normalizedName;
		this.kind = kind;
		this.bakeryItem = bakeryItem;
		this.confidence = confidence;
		this.agreed = agreed;
		this.model = model;
	}

	public static MenuNameClassification from(MenuKindVerdict verdict, String model) {
		return new MenuNameClassification(
			verdict.normalizedName(),
			verdict.kind(),
			verdict.bakeryItem(),
			(float)verdict.confidence(),
			verdict.agreed(),
			model);
	}

	/** 사람이 정한 판정 — 모델 자리엔 ADMIN 을 남겨 AI 판정과 구분한다 */
	public static final String ADMIN_MODEL = "ADMIN";

	public static MenuNameClassification confirmedByAdmin(String normalizedName, MenuKind kind, boolean bakeryItem) {
		return new MenuNameClassification(normalizedName, kind, bakeryItem, null, true, ADMIN_MODEL);
	}

	/** 이미 AI 가 남긴 행(갈렸거나 틀린 것)을 사람이 덮어쓴다 */
	public void confirmByAdmin(MenuKind kind, boolean bakeryItem) {
		this.kind = kind;
		this.bakeryItem = bakeryItem;
		this.confidence = null;
		this.agreed = true;
		this.model = ADMIN_MODEL;
	}

	public MenuKindVerdict toVerdict() {
		return new MenuKindVerdict(normalizedName, kind, bakeryItem, confidence == null ? 0 : confidence, agreed);
	}
}
