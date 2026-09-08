package sweetgirl.croissant.menu.dto.classification;

import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

/**
 * 메뉴 이름 하나에 대한 AI 분류 판정.
 *
 * @param normalizedName 캐시 키
 * @param kind           확정 kind. null = 세 번이 갈렸거나 '빵'·'기타'로만 답함 → 미분류로 남기고 검수
 * @param bakeryItem     빵집 먹거리인가. false 면 kind 는 '기타'
 * @param confidence     모델 자기보고 평균 — 저장만, 판단엔 안 씀
 * @param agreed         3회 답이 모두 같았는가
 */
public record MenuKindVerdict(
		String normalizedName,
		MenuKind kind,
		boolean bakeryItem,
		double confidence,
		boolean agreed) {

	/** 음료·식사·굿즈 — '기타'로 확정. 세 번 중 과반이 비-빵이라 했으면 여기 */
	public static MenuKindVerdict nonBakery(String normalizedName, double confidence) {
		return new MenuKindVerdict(normalizedName, MenuKind.기타, false, confidence, true);
	}

	/** 세 번 다 같은 구체 kind */
	public static MenuKindVerdict agreed(String normalizedName, MenuKind kind, double confidence) {
		return new MenuKindVerdict(normalizedName, kind, true, confidence, true);
	}

	/** 답이 갈렸거나 '빵'·'기타'로만 답함 — 이름만으론 모른다는 뜻. 카테고리를 주지 않는다 */
	public static MenuKindVerdict undecided(String normalizedName, double confidence) {
		return new MenuKindVerdict(normalizedName, null, true, confidence, false);
	}

	/** 메뉴에 카테고리를 써도 되는가 — 확정 kind 가 있을 때만 */
	public boolean decided() {
		return kind != null;
	}

	/** 폴백 kind 로 합의된 건 "모르겠다"와 같다 */
	public static boolean isFallbackKind(MenuKind kind) {
		return MenuCategory.UNCATEGORIZED_KINDS.contains(kind);
	}
}
