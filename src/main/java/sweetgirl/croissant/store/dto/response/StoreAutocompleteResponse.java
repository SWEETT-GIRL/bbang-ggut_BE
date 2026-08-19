package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.search.StoreAutocompleteSuggestion;

public record StoreAutocompleteResponse(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId) {

	public static StoreAutocompleteResponse from(StoreAutocompleteSuggestion suggestion) {
		return new StoreAutocompleteResponse(suggestion.keyword(), suggestion.type(), suggestion.storeId());
	}
}
