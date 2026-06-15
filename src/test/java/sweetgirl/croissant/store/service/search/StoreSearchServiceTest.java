package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
		// 공백 분해로 이미 빵 토큰이 잡힌 경우 — 폴백 분해 결과가 1차와 동일하므로 재검색하지 않는다
		SearchIntent fallbackIntent = new SearchIntent("성수 에그타르트", "성수 에그타르트", List.of("성수", "에그타르트"),
			List.of("성수"), List.of("에그타르트"), List.of("에그타르트"));
		PageRequest pageable = PageRequest.of(0, 15);
		given(storeSearchQueryParser.parse("성수 에그타르트")).willReturn(intent);
		given(storeSearchQueryParser.parseWithBreadFallback("성수 에그타르트")).willReturn(fallbackIntent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(OpenSearchStoreSearchResult.empty());

		var result = service.searchStores("성수 에그타르트", null, null, SearchSort.DISTANCE, pageable);

		assertThat(result.content()).isEmpty();
		assertThat(result.hasNext()).isFalse();
		verify(storeSearchAssembler, never()).assemble(any());
		// 폴백 빵 토큰이 1차와 같으므로 OpenSearch 재요청은 없어야 한다
		verify(openSearchStoreClient, times(1)).searchStores(any(), any(), isNull(), isNull(), any());
	}

	@Test
	void searchStores_runsBreadFallbackWhenPrimaryEmptyAndBreadDecomposable() {
		StoreSearchService service = new StoreSearchService(
			storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler);
		// 1차: "초코소금빵"을 통째로 가게명/메뉴 검색 (빵 미분해)
		SearchIntent primary = new SearchIntent("초코소금빵", "초코소금빵", List.of("초코소금빵"),
			List.of(), List.of(), List.of("초코소금빵"));
		// 폴백: "소금빵"(must) + "초코"(remaining)로 분해
		SearchIntent fallback = new SearchIntent("초코소금빵", "초코소금빵", List.of("초코소금빵"),
			List.of(), List.of("소금빵"), List.of("초코"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(new OpenSearchStoreHit(1L, 1.0, null, List.of(), List.of()));
		given(storeSearchQueryParser.parse("초코소금빵")).willReturn(primary);
		given(openSearchStoreClient.searchStores(primary, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(OpenSearchStoreSearchResult.empty());
		given(storeSearchQueryParser.parseWithBreadFallback("초코소금빵")).willReturn(fallback);
		given(openSearchStoreClient.searchStores(fallback, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("초코소금빵", null, null, SearchSort.DISTANCE, pageable);

		// 1차가 0건이라 폴백 인텐트로 재검색하고, 그 결과를 조립한다
		verify(openSearchStoreClient).searchStores(fallback, pageable, null, null, SearchSort.DISTANCE);
		verify(storeSearchAssembler).assemble(hits);
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
