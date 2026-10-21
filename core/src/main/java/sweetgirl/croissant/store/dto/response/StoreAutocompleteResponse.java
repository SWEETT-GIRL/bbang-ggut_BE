package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.search.StoreAutocompleteSuggestion;

public record StoreAutocompleteResponse(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId,
		/** REGION 만 채워진다 — 고르면 지도가 갈 자리와 당길 정도 */
		Double centerLat,
		Double centerLng,
		Integer radiusMeters,
		/** REGION 만 — "중동"이 어느 중동인지 가른다(부천시·해운대구·마포구 …) */
		String parentName) {

	/** 지역이 아닌 후보는 좌표가 없다 */
	public StoreAutocompleteResponse(String keyword, AutocompleteSuggestionType type, Long storeId) {
		this(keyword, type, storeId, null, null, null, null);
	}

	public static StoreAutocompleteResponse from(StoreAutocompleteSuggestion suggestion) {
		return new StoreAutocompleteResponse(suggestion.keyword(), suggestion.type(), suggestion.storeId(),
			suggestion.centerLat(), suggestion.centerLng(), suggestion.radiusMeters(), suggestion.parentName());
	}
}
