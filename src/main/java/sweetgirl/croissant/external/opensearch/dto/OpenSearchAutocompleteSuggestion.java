package sweetgirl.croissant.external.opensearch.dto;

import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

public record OpenSearchAutocompleteSuggestion(
		String keyword,
		AutocompleteSuggestionType type,
		Long storeId) {
}
