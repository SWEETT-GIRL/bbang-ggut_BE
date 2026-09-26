package sweetgirl.croissant.store.service.search;

import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

/** 검색 엔진이 돌려주는 자동완성 후보 한 건. 서비스가 타입 필터·중복 제거·우선순위 정렬을 한다. */
public record StoreAutocompleteSuggestion(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId,
		Double centerLat,
		Double centerLng,
		Integer radiusMeters,
		String parentName) {

	/** 지역이 아닌 후보(가게·메뉴·빵종류)는 좌표가 없다 */
	public StoreAutocompleteSuggestion(String keyword, AutocompleteSuggestionType type, Long storeId) {
		this(keyword, type, storeId, null, null, null, null);
	}
}
