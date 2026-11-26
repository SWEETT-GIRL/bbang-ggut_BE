package sweetgirl.dataops.serving.dto;

/**
 * menu.add 반영 결과 — 같은 가게에 운영 정규화 이름이 같은 메뉴가 이미 있으면 삽입하지 않는다(2026-09-06).
 *
 * <ul>
 *   <li>INSERTED — 새 행</li>
 *   <li>UPDATED — 이름까지 같은 기존 메뉴(공백·기호·대소문자만 다름)의 가격·설명을 갱신</li>
 *   <li>BLOCKED_USER_MENU — 사용자가 앱에서 직접 등록한 메뉴와 겹쳐 아무것도 쓰지 않음</li>
 *   <li>BLOCKED_DISTINCT_NAME — 운영 정규화([[:alnum:]])로는 같은 키지만 이름이 다른 별개 상품
 *       (예: 답례세트① vs 답례세트③, 生食パン(생식빵) vs 생식빵) — 덮어쓰면 다른 상품의 가격이 되므로 쓰지 않음</li>
 * </ul>
 *
 * @param menuId              삽입·갱신된 menu.id, 막힌 경우엔 겹친 메뉴의 id
 * @param previousPrice       갱신 전 가격 (UPDATED 일 때만)
 * @param previousDescription 갱신 전 설명 (UPDATED 일 때만)
 */
public record MenuUpsertResult(Long menuId, Outcome outcome, Integer previousPrice, String previousDescription) {

	public enum Outcome { INSERTED, UPDATED, BLOCKED_USER_MENU, BLOCKED_DISTINCT_NAME, BLOCKED_EXISTING_MENU }

	public static MenuUpsertResult inserted(Long menuId) {
		return new MenuUpsertResult(menuId, Outcome.INSERTED, null, null);
	}

	public static MenuUpsertResult updated(Long menuId, Integer previousPrice, String previousDescription) {
		return new MenuUpsertResult(menuId, Outcome.UPDATED, previousPrice, previousDescription);
	}

	public static MenuUpsertResult blockedByUserMenu(Long menuId) {
		return new MenuUpsertResult(menuId, Outcome.BLOCKED_USER_MENU, null, null);
	}

	public static MenuUpsertResult blockedByDistinctName(Long menuId) {
		return new MenuUpsertResult(menuId, Outcome.BLOCKED_DISTINCT_NAME, null, null);
	}

	public static MenuUpsertResult blockedByExistingMenu(Long menuId) {
		return new MenuUpsertResult(menuId, Outcome.BLOCKED_EXISTING_MENU, null, null);
	}

	public boolean inserted() {
		return outcome == Outcome.INSERTED;
	}

	public boolean updated() {
		return outcome == Outcome.UPDATED;
	}

	public boolean blocked() {
		return outcome == Outcome.BLOCKED_USER_MENU || outcome == Outcome.BLOCKED_DISTINCT_NAME
			|| outcome == Outcome.BLOCKED_EXISTING_MENU;
	}
}
