package sweetgirl.croissant.store.service.search;

import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

/**
 * 검색 엔진이 돌려주는 자동완성 후보 한 건. 서비스가 타입 필터·중복 제거·우선순위 정렬·인기순·급부상 정렬을 한다.
 *
 * @param tier        0 = 접두 일치 — 승격은 접두 일치 가게만 한다
 * @param popularityScore 가게의 인기도 — 지역 위로 올릴 가게를 고르고 세우는 값. 캐시에 남은 옛 값에는 없어서(null) 비교 대상에서 빠진다
 */
public record StoreAutocompleteSuggestion(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId,
		Double centerLat,
		Double centerLng,
		Integer radiusMeters,
		String parentName,
		Integer tier,
		Double popularityScore) {

	/** 지역이 아닌 후보(가게·메뉴·빵종류)는 좌표가 없다 */
	public StoreAutocompleteSuggestion(String keyword, AutocompleteSuggestionType type, Long storeId) {
		this(keyword, type, storeId, null, null, null, null, null, null);
	}

	public StoreAutocompleteSuggestion(String keyword, AutocompleteSuggestionType type, Long storeId,
			Double centerLat, Double centerLng, Integer radiusMeters, String parentName) {
		this(keyword, type, storeId, centerLat, centerLng, radiusMeters, parentName, null, null);
	}

	/** 접두 일치 가게이면서 인기도가 기준 이상인가 */
	public boolean isPopularCandidate(double minScore) {
		return type == AutocompleteSuggestionType.STORE && tier != null && tier == 0 && popularityScore != null
			&& popularityScore >= minScore;
	}
}
