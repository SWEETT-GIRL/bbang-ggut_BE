package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.external.opensearch.client.OpenSearchStoreClient;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchAutocompleteSuggestion;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

@ExtendWith(MockitoExtension.class)
class StoreSearchServiceTest {

	@Mock
	private StoreSearchQueryParser storeSearchQueryParser;

	@Mock
	private OpenSearchStoreClient openSearchStoreClient;

	@Mock
	private StoreSearchAssembler storeSearchAssembler;

	@Test
	void searchStores_returnsEmptyWithoutFallbackWhenOpenSearchHasNoResult() {
		StoreSearchService service = new StoreSearchService(
			storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler);
		SearchIntent intent = new SearchIntent("성수 에그타르트", List.of("성수", "에그타르트"),
			List.of("성수"), List.of("에그타르트"), List.of("에그타르트"));
		PageRequest pageable = PageRequest.of(0, 15);
		given(storeSearchQueryParser.parse("성수 에그타르트")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(OpenSearchStoreSearchResult.empty());

		var result = service.searchStores("성수 에그타르트", null, null, SearchSort.DISTANCE, pageable);

		assertThat(result.content()).isEmpty();
		assertThat(result.hasNext()).isFalse();
		verify(storeSearchAssembler, never()).assemble(any());
	}

	@Test
	void searchStores_assemblesOpenSearchHits() {
		StoreSearchService service = new StoreSearchService(
			storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler);
		SearchIntent intent = new SearchIntent("소금빵", List.of("소금빵"), List.of(), List.of("소금빵"),
			List.of("소금빵"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(new OpenSearchStoreHit(1L, 1.0, null, List.of(), List.of()));
		given(storeSearchQueryParser.parse("소금빵")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, true));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		var result = service.searchStores("소금빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(result.hasNext()).isTrue();
		verify(openSearchStoreClient).searchStores(eq(intent), eq(pageable), isNull(), isNull(),
			eq(SearchSort.RELEVANCE));
	}

	@Test
	void autocompleteStores_deduplicatesByTypeAndKeywordAndMovesStoresToBottom() {
		StoreSearchService service = new StoreSearchService(
			storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler);
		given(openSearchStoreClient.autocompleteStores("소금", 3)).willReturn(List.of(
			new OpenSearchAutocompleteSuggestion("소금집델리", AutocompleteSuggestionType.STORE, 1L),
			new OpenSearchAutocompleteSuggestion("소금빵", AutocompleteSuggestionType.BREAD_TYPE, null),
			new OpenSearchAutocompleteSuggestion("소금빵", AutocompleteSuggestionType.BREAD_TYPE, null),
			new OpenSearchAutocompleteSuggestion("트러플 소금빵", AutocompleteSuggestionType.MENU, null),
			new OpenSearchAutocompleteSuggestion("소금집델리", AutocompleteSuggestionType.STORE, 2L)));

		var result = service.autocompleteStores("소금", 3);

		assertThat(result).hasSize(3);
		assertThat(result.get(0).keyword()).isEqualTo("소금빵");
		assertThat(result.get(0).type()).isEqualTo(AutocompleteSuggestionType.BREAD_TYPE);
		assertThat(result.get(1).keyword()).isEqualTo("트러플 소금빵");
		assertThat(result.get(1).type()).isEqualTo(AutocompleteSuggestionType.MENU);
		assertThat(result.get(2).keyword()).isEqualTo("소금집델리");
		assertThat(result.get(2).storeId()).isEqualTo(1L);
	}

	@Test
	void autocompleteStores_prioritizesRegionBeforeMenu() {
		StoreSearchService service = new StoreSearchService(
			storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler);
		given(openSearchStoreClient.autocompleteStores("망원", 3)).willReturn(List.of(
			new OpenSearchAutocompleteSuggestion("망원동 찐 초코 바게트", AutocompleteSuggestionType.MENU, null),
			new OpenSearchAutocompleteSuggestion("파운드마켓 망원점", AutocompleteSuggestionType.STORE, 4619L),
			new OpenSearchAutocompleteSuggestion("망원", AutocompleteSuggestionType.REGION, null)));

		var result = service.autocompleteStores("망원", 3);

		assertThat(result).hasSize(3);
		assertThat(result.get(0).keyword()).isEqualTo("망원");
		assertThat(result.get(0).type()).isEqualTo(AutocompleteSuggestionType.REGION);
		assertThat(result.get(1).type()).isEqualTo(AutocompleteSuggestionType.MENU);
		assertThat(result.get(2).type()).isEqualTo(AutocompleteSuggestionType.STORE);
	}

	@Test
	void autocompleteStores_returnsEmptyWhenQueryIsBlank() {
		StoreSearchService service = new StoreSearchService(
			storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler);

		var result = service.autocompleteStores("  ", 10);

		assertThat(result).isEmpty();
		verify(openSearchStoreClient, never()).autocompleteStores(any(), anyInt());
	}
}
