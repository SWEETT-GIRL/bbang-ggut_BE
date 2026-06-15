package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;

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
		SearchIntent intent = new SearchIntent("성수 에그타르트", "성수 에그타르트", List.of("성수", "에그타르트"),
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
		SearchIntent intent = new SearchIntent("소금빵", "소금빵", List.of("소금빵"), List.of(), List.of("소금빵"),
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
}
