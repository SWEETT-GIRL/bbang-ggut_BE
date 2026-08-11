package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

	private SimpleMeterRegistry meterRegistry;
	private StoreSearchService service;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		// 판정 규칙은 순수 로직이라 목킹하지 않고 실제 구현을 쓴다.
		service = new StoreSearchService(storeSearchQueryParser, openSearchStoreClient, storeSearchAssembler,
			new SearchQueryCoverage(), meterRegistry);
		// 운영에서는 @PostConstruct로 실행되는 시계열 사전 등록을 동일하게 태운다.
		service.registerSearchOutcomeCounters();
	}

	private double outcomeCount(String type, SearchOutcome outcome) {
		Counter counter = meterRegistry.find("search.query")
			.tag("type", type)
			.tag("outcome", outcome.tagValue())
			.counter();
		return counter == null ? -1 : counter.count();
	}

	private OpenSearchStoreHit hit() {
		return new OpenSearchStoreHit(1L, 1.0, null, List.of(), List.of());
	}

	@Test
	void searchStores_returnsEmptyWithoutFallbackWhenOpenSearchHasNoResult() {
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
		// 1차: "초코소금빵"을 통째로 가게명/메뉴 검색 (빵 미분해)
		SearchIntent primary = new SearchIntent("초코소금빵", "초코소금빵", List.of("초코소금빵"),
			List.of(), List.of(), List.of("초코소금빵"));
		// 폴백: "소금빵"(must) + "초코"(remaining)로 분해
		SearchIntent fallback = new SearchIntent("초코소금빵", "초코소금빵", List.of("초코소금빵"),
			List.of(), List.of("소금빵"), List.of("초코"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("초코소금빵")).willReturn(primary);
		given(openSearchStoreClient.searchStores(primary, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(OpenSearchStoreSearchResult.empty());
		given(storeSearchQueryParser.parseWithBreadFallback("초코소금빵")).willReturn(fallback);
		given(openSearchStoreClient.searchStores(fallback, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("초코소금빵", null, null, SearchSort.DISTANCE, pageable);

		// 1차가 0건이라 폴백 인텐트로 재검색하고, 그 결과를 조립한다
		verify(openSearchStoreClient).searchStores(fallback, pageable, null, null, SearchSort.DISTANCE);
		verify(storeSearchAssembler).assemble(hits);
	}

	@Test
	void searchStores_assemblesOpenSearchHits() {
		SearchIntent intent = new SearchIntent("소금빵", "소금빵", List.of("소금빵"), List.of(), List.of("소금빵"),
			List.of("소금빵"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("소금빵")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, true, true));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		var result = service.searchStores("소금빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(result.hasNext()).isTrue();
		verify(openSearchStoreClient).searchStores(eq(intent), eq(pageable), isNull(), isNull(),
			eq(SearchSort.RELEVANCE));
	}

	@Test
	@DisplayName("지표 — 폴백까지 돌려도 0건이면 empty로 집계한다")
	void searchStores_recordsEmptyOutcome() {
		SearchIntent intent = new SearchIntent("없는빵", "없는빵", List.of("없는빵"),
			List.of(), List.of(), List.of("없는빵"));
		PageRequest pageable = PageRequest.of(0, 15);
		given(storeSearchQueryParser.parse("없는빵")).willReturn(intent);
		given(storeSearchQueryParser.parseWithBreadFallback("없는빵")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(OpenSearchStoreSearchResult.empty());

		service.searchStores("없는빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(outcomeCount("search", SearchOutcome.EMPTY)).isEqualTo(1.0);
		assertThat(outcomeCount("search", SearchOutcome.HIT)).isZero();
	}

	@Test
	@DisplayName("지표 — 빵 분해 폴백으로 건진 결과는 fallback으로 집계한다")
	void searchStores_recordsFallbackOutcome() {
		SearchIntent primary = new SearchIntent("초코소금빵", "초코소금빵", List.of("초코소금빵"),
			List.of(), List.of(), List.of("초코소금빵"));
		SearchIntent fallback = new SearchIntent("초코소금빵", "초코소금빵", List.of("초코소금빵"),
			List.of(), List.of("소금빵"), List.of("초코"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("초코소금빵")).willReturn(primary);
		given(storeSearchQueryParser.parseWithBreadFallback("초코소금빵")).willReturn(fallback);
		given(openSearchStoreClient.searchStores(primary, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(OpenSearchStoreSearchResult.empty());
		given(openSearchStoreClient.searchStores(fallback, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("초코소금빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(outcomeCount("search", SearchOutcome.FALLBACK)).isEqualTo(1.0);
		assertThat(outcomeCount("search", SearchOutcome.EMPTY)).isZero();
	}

	@Test
	@DisplayName("지표 — 입력한 말이 결과에 있으면 hit으로 집계한다")
	void searchStores_recordsHitOutcome() {
		SearchIntent intent = new SearchIntent("성수동 소금빵", "성수동 소금빵", List.of("성수동", "소금빵"),
			List.of("성수동"), List.of("소금빵"), List.of());
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("성수동 소금빵")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, false, true));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("성수동 소금빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(outcomeCount("search", SearchOutcome.HIT)).isEqualTo(1.0);
	}

	@Test
	@DisplayName("지표 — 전부 지역·빵으로 분류된 검색어가 결과에 없으면 mismatch로 집계한다")
	void searchStores_recordsMismatchOutcomeForFullyClassifiedQuery() {
		SearchIntent intent = new SearchIntent("성수동 소금빵", "성수동 소금빵", List.of("성수동", "소금빵"),
			List.of("성수동"), List.of("소금빵"), List.of());
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("성수동 소금빵")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("성수동 소금빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(outcomeCount("search", SearchOutcome.MISMATCH)).isEqualTo(1.0);
		assertThat(outcomeCount("search", SearchOutcome.MISMATCH_LOOSE)).isZero();
	}

	@Test
	@DisplayName("지표 — 사전에 없는 말이 섞인 검색어가 결과에 없으면 오탐 가능성을 구분해 mismatch_loose로 집계한다")
	void searchStores_recordsMismatchLooseOutcomeForUnclassifiedQuery() {
		// "베이커리"는 사전에 없어 잔여 토큰으로 남는다 — 통째 대조라 오탐이 섞일 수 있는 판정이다.
		SearchIntent intent = new SearchIntent("맘모스 베이커리", "맘모스 베이커리", List.of("맘모스", "베이커리"),
			List.of(), List.of("맘모스"), List.of("베이커리"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<OpenSearchStoreHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("맘모스 베이커리")).willReturn(intent);
		given(openSearchStoreClient.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new OpenSearchStoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("맘모스 베이커리", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(outcomeCount("search", SearchOutcome.MISMATCH_LOOSE)).isEqualTo(1.0);
		assertThat(outcomeCount("search", SearchOutcome.MISMATCH)).isZero();
	}

	@Test
	@DisplayName("지표 — outcome 시계열은 부팅 시 전부 등록되어 발생 전에도 0으로 노출된다")
	void registerSearchOutcomeCounters_preRegistersEverySeries() {
		for (SearchOutcome outcome : SearchOutcome.values()) {
			assertThat(outcomeCount("search", outcome)).isZero();
		}
		assertThat(outcomeCount("autocomplete", SearchOutcome.HIT)).isZero();
		assertThat(outcomeCount("autocomplete", SearchOutcome.EMPTY)).isZero();
	}

	@Test
	void autocompleteStores_deduplicatesByTypeAndKeywordAndMovesStoresToBottom() {
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
		assertThat(outcomeCount("autocomplete", SearchOutcome.HIT)).isEqualTo(1.0);
	}

	@Test
	void autocompleteStores_prioritizesRegionBeforeMenu() {
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
	@DisplayName("지표 — 자동완성 결과가 0건이면 empty로 집계한다")
	void autocompleteStores_recordsEmptyOutcome() {
		given(openSearchStoreClient.autocompleteStores("ㅁㄴㅇㄹ", 10)).willReturn(List.of());

		var result = service.autocompleteStores("ㅁㄴㅇㄹ", 10);

		assertThat(result).isEmpty();
		assertThat(outcomeCount("autocomplete", SearchOutcome.EMPTY)).isEqualTo(1.0);
	}

	@Test
	void autocompleteStores_returnsEmptyWhenQueryIsBlank() {
		var result = service.autocompleteStores("  ", 10);

		assertThat(result).isEmpty();
		verify(openSearchStoreClient, never()).autocompleteStores(any(), anyInt());
		// 빈 입력은 검색 시도가 아니므로 집계하지 않는다 (시계열은 사전 등록되어 있으므로 0)
		assertThat(outcomeCount("autocomplete", SearchOutcome.EMPTY)).isZero();
	}
}
