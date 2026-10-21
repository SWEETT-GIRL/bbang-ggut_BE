package sweetgirl.dataops.proposal.enums;

import java.util.regex.Pattern;

/**
 * 재판정 범위의 대상 묶음 (v3 P1-2) — "이 판정이 무엇을 다시 봤는지"를 나타낸다.
 *
 * 한 판정이 다루지 않은 묶음의 제안은 그 판정의 정리 대상이 아니다. 예: 네이버 스냅샷 재판정은
 * 주소를 보지만 지역은 보지 않으므로, 주소와 지역을 한 묶음으로 두면 지역 제안이 함께 지워진다.
 * 그래서 묶음은 실제 판정 주체가 다루는 경계에 맞춰 잘게 나눈다.
 */
public enum ProposalTargetFamily {
	STORE_NAME,
	STORE_PHONE,
	STORE_INSTAGRAM,
	STORE_ADDRESS,
	STORE_REGION,
	STORE_STATUS,
	STORE_CATEGORY,
	STORE_NAVER_PLACE_ID,
	BUSINESS_HOURS,
	/** menu.add.* / menu.{id}.price / menu.{id}.description — 살아 있는 메뉴의 내용 */
	MENU_CONTENT,
	/** menu.{id}.removed — 소멸·정책 제외 */
	MENU_REMOVAL,
	/** menu.{id}.category — 종류 재분류 */
	MENU_CATEGORY,
	/** 위 어디에도 해당하지 않는 대상 — 새 target 을 추가하면 여기 걸린다 */
	UNCLASSIFIED;

	private static final Pattern MENU_FIELD = Pattern.compile("^menu\\.\\d+\\.(\\w+)$");

	public static ProposalTargetFamily of(String target) {
		if (target == null) {
			return UNCLASSIFIED;
		}
		switch (target) {
			case "store.name": return STORE_NAME;
			case "store.phone": return STORE_PHONE;
			case "store.instagram_handle": return STORE_INSTAGRAM;
			case "store.address": return STORE_ADDRESS;
			case "store.region": return STORE_REGION;
			case "store.status": return STORE_STATUS;
			case "store.category": return STORE_CATEGORY;
			case "store.naver_place_id": return STORE_NAVER_PLACE_ID;
			case "store.business_hours": return BUSINESS_HOURS;
			default: break;
		}
		if (target.startsWith("menu.add.")) {
			return MENU_CONTENT;
		}
		java.util.regex.Matcher matcher = MENU_FIELD.matcher(target);
		if (matcher.matches()) {
			switch (matcher.group(1)) {
				case "price":
				case "description": return MENU_CONTENT;
				case "removed": return MENU_REMOVAL;
				case "category": return MENU_CATEGORY;
				default: return UNCLASSIFIED;
			}
		}
		return UNCLASSIFIED;
	}
}
