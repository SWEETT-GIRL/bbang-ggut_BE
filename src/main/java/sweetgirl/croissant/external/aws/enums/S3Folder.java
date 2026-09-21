package sweetgirl.croissant.external.aws.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum S3Folder {
	STORES("stores"),
	MENUS("menus"),
	REVIEWS("reviews"),
	REPORTS("reports"),
	MENU_ADD_REPORTS("menu-add-reports"),
	BANNERS("banners"),
	COURSES("courses"),
	BREAD_RECORDS("bread-records"),
	/**
	 * 빵별일지 세계관 에셋(캐릭터 누끼 · 행성 배경).
	 *
	 * <p>⚠️ 리사이즈 Lambda 가 버킷 전체에 걸려 있다. 이 프리픽스에 올리기 전에
	 * {@code bbanggut-dev-image-resizer} · {@code bbanggut-prod-image-resizer} 둘 다
	 * {@code world/} 가드가 들어가 있어야 한다. 없으면 투명 PNG 가 webp 로 구워져 배경이 날아간다.
	 */
	WORLD("world");

	private final String value;

	public String prefix() {
		return value + "/";
	}
}
