package sweetgirl.croissant.store.service.search;

import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

/** 검색 엔진이 돌려주는 자동완성 후보 한 건. 서비스가 타입 필터·중복 제거·우선순위 정렬을 한다. */
public record StoreAutocompleteSuggestion(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId) {
}
