package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.external.opensearch.dto.OpenSearchAutocompleteSuggestion;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

public record StoreAutocompleteResponse(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId) {

	public static StoreAutocompleteResponse from(OpenSearchAutocompleteSuggestion suggestion) {
		return new StoreAutocompleteResponse(suggestion.keyword(), suggestion.type(), suggestion.storeId());
	}
}
