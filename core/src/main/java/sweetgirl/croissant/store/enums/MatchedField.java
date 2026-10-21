package sweetgirl.croissant.store.enums;

/**
 * 검색 결과 한 건이 검색어와 어디서 매칭됐는지 — 클라이언트가 하이라이트에 쓴다.
 * BREAD_TYPE·MENU 는 메뉴 검색이 있던 시절의 값으로, 지금 검색(가게명·지역)은 내지 않지만 응답 계약 호환을 위해 남긴다.
 */
public enum MatchedField {
	STORE_NAME,
	REGION,
	BREAD_TYPE,
	MENU
}
