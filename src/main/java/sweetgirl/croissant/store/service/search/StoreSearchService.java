package sweetgirl.croissant.store.service.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.opensearch.client.OpenSearchStoreClient;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchAutocompleteSuggestion;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreSearchService {

	private static final int DEFAULT_AUTOCOMPLETE_SIZE = 10;
	private static final int MAX_AUTOCOMPLETE_SIZE = 20;

	private final StoreSearchQueryParser storeSearchQueryParser;
	private final OpenSearchStoreClient openSearchStoreClient;
	private final StoreSearchAssembler storeSearchAssembler;

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Double latitude, Double longitude,
			SearchSort sort, Pageable pageable) {
		// 1차: 가게명 우선 — "브릭베이글"처럼 붙여쓴 토큰은 통째로 가게명/메뉴 검색
		SearchIntent intent = storeSearchQueryParser.parse(keyword);
		OpenSearchStoreSearchResult result = openSearchStoreClient.searchStores(intent, pageable, latitude, longitude,
			sort);
		// 폴백: 1차가 0건이고, 붙어쓴 토큰 속 빵 단어를 분해할 수 있으면("초코소금빵"→"소금빵" 메뉴 필터)
		// 해당 메뉴를 가진 가게를 다시 검색한다. 빵 분해 결과가 1차와 같으면(분해할 빵이 없으면) 재검색하지 않는다.
		if (result.hits().isEmpty()) {
			SearchIntent fallbackIntent = storeSearchQueryParser.parseWithBreadFallback(keyword);
			if (!fallbackIntent.breadTokens().equals(intent.breadTokens())) {
				result = openSearchStoreClient.searchStores(fallbackIntent, pageable, latitude, longitude, sort);
			}
		}
		if (result.hits().isEmpty()) {
			return new SliceResponse<>(List.of(), false);
		}
		return new SliceResponse<>(storeSearchAssembler.assemble(result.hits()), result.hasNext());
	}

	public List<StoreAutocompleteResponse> autocompleteStores(String query, int size) {
		if (!StringUtils.hasText(query)) {
			return List.of();
		}

		int normalizedSize = normalizeAutocompleteSize(size);
		List<OpenSearchAutocompleteSuggestion> suggestions = openSearchStoreClient.autocompleteStores(query,
			normalizedSize);
		Map<String, StoreAutocompleteResponse> uniqueSuggestions = new LinkedHashMap<>();
		for (OpenSearchAutocompleteSuggestion suggestion : suggestions) {
			StoreAutocompleteResponse response = StoreAutocompleteResponse.from(suggestion);
			uniqueSuggestions.putIfAbsent(response.type() + ":" + response.keyword(), response);
		}
		return uniqueSuggestions.values().stream()
			.sorted((left, right) -> Integer.compare(autocompletePriority(left), autocompletePriority(right)))
			.limit(normalizedSize)
			.toList();
	}

	private int normalizeAutocompleteSize(int size) {
		if (size <= 0) {
			return DEFAULT_AUTOCOMPLETE_SIZE;
		}
		return Math.min(size, MAX_AUTOCOMPLETE_SIZE);
	}

	private int autocompletePriority(StoreAutocompleteResponse response) {
		return switch (response.type()) {
			case REGION -> 0;
			case BREAD_TYPE -> 1;
			case MENU -> 2;
			case STORE -> 3;
		};
	}
}
