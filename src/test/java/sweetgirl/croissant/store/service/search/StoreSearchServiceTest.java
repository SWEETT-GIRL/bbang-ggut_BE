package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.searchmiss.event.SearchMissEvent;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

@ExtendWith(MockitoExtension.class)
class StoreSearchServiceTest {

	@Mock
	private StoreSearchQueryParser storeSearchQueryParser;

	@Mock
	private StoreSearchEngine searchEngine;

	@Mock
	private StoreSearchAssembler storeSearchAssembler;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private SimpleMeterRegistry meterRegistry;
	private StoreSearchService service;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		// 판정 규칙은 순수 로직이라 목킹하지 않고 실제 구현을 쓴다.
		service = new StoreSearchService(storeSearchQueryParser, searchEngine, storeSearchAssembler,
			meterRegistry, eventPublisher);
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

	private StoreSearchHit hit() {
		return new StoreSearchHit(1L, 1.0, null, List.of(), List.of());
	}

	@Test
	void searchStores_returnsEmptyWithoutFallbackWhenEngineHasNoResult() {
		SearchIntent intent = new SearchIntent("성수 에그타르트", "성수 에그타르트", List.of("성수", "에그타르트"),
			List.of("성수"), List.of("에그타르트"), List.of("에그타르트"));
		// 공백 분해로 이미 빵 토큰이 잡힌 경우 — 폴백 분해 결과가 1차와 동일하므로 재검색하지 않는다
		SearchIntent fallbackIntent = new SearchIntent("성수 에그타르트", "성수 에그타르트", List.of("성수", "에그타르트"),
			List.of("성수"), List.of("에그타르트"), List.of("에그타르트"));
		PageRequest pageable = PageRequest.of(0, 15);
		given(storeSearchQueryParser.parse("성수 에그타르트")).willReturn(intent);
		given(storeSearchQueryParser.parseWithGluedTokenFallback("성수 에그타르트")).willReturn(fallbackIntent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(StoreSearchResult.empty());

		var result = service.searchStores("성수 에그타르트", null, null, SearchSort.DISTANCE, pageable);

		assertThat(result.content()).isEmpty();
		assertThat(result.hasNext()).isFalse();
		verify(storeSearchAssembler, never()).assemble(any());
		// 폴백 빵 토큰이 1차와 같으므로 엔진 재요청은 없어야 한다
		verify(searchEngine, times(1)).searchStores(any(), any(), isNull(), isNull(), any());
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
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("초코소금빵")).willReturn(primary);
		given(searchEngine.searchStores(primary, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(StoreSearchResult.empty());
		given(storeSearchQueryParser.parseWithGluedTokenFallback("초코소금빵")).willReturn(fallback);
		given(searchEngine.searchStores(fallback, pageable, null, null, SearchSort.DISTANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("초코소금빵", null, null, SearchSort.DISTANCE, pageable);

		// 1차가 0건이라 폴백 인텐트로 재검색하고, 그 결과를 조립한다
		verify(searchEngine).searchStores(fallback, pageable, null, null, SearchSort.DISTANCE);
		verify(storeSearchAssembler).assemble(hits);
	}

	@Test
	@DisplayName("폴백 — 지역 토큰만 새로 분해돼도 재검색한다")
	void searchStores_runsFallbackWhenOnlyRegionDecomposed() {
		// "도화베이커리" 같은 검색어는 1차에서 통째로 두므로 0건일 수 있다.
		// 빵 토큰만 비교하면 재검색이 걸리지 않아 그대로 0건으로 끝난다.
		SearchIntent primary = new SearchIntent("도화베이커리", "도화베이커리", List.of("도화베이커리"),
			List.of(), List.of(), List.of("도화베이커리"));
		SearchIntent fallback = new SearchIntent("도화베이커리", "도화베이커리", List.of("도화베이커리"),
			List.of("도화"), List.of(), List.of("베이커리"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("도화베이커리")).willReturn(primary);
		given(storeSearchQueryParser.parseWithGluedTokenFallback("도화베이커리")).willReturn(fallback);
		given(searchEngine.searchStores(primary, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(StoreSearchResult.empty());
		given(searchEngine.searchStores(fallback, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("도화베이커리", null, null, SearchSort.RELEVANCE, pageable);

		verify(searchEngine).searchStores(fallback, pageable, null, null, SearchSort.RELEVANCE);
		assertThat(outcomeCount("search", SearchOutcome.FALLBACK)).isEqualTo(1.0);
	}

	@Test
	@DisplayName("폴백 — 지역·빵 어느 쪽도 분해되지 않으면 재검색하지 않는다")
	void searchStores_skipsFallbackWhenNothingDecomposed() {
		SearchIntent intent = new SearchIntent("대쟌", "대쟌", List.of("대쟌"),
			List.of(), List.of(), List.of("대쟌"));
		PageRequest pageable = PageRequest.of(0, 15);
		given(storeSearchQueryParser.parse("대쟌")).willReturn(intent);
		given(storeSearchQueryParser.parseWithGluedTokenFallback("대쟌")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(StoreSearchResult.empty());

		service.searchStores("대쟌", null, null, SearchSort.RELEVANCE, pageable);

		// 같은 인텐트로 두 번 부르는 낭비를 막는다
		verify(searchEngine, times(1)).searchStores(any(), any(), isNull(), isNull(), any());
		assertThat(outcomeCount("search", SearchOutcome.EMPTY)).isEqualTo(1.0);
	}

	@Test
	void searchStores_assemblesEngineHits() {
		SearchIntent intent = new SearchIntent("소금빵", "소금빵", List.of("소금빵"), List.of(), List.of("소금빵"),
			List.of("소금빵"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("소금빵")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, true, true));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		var result = service.searchStores("소금빵", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(result.hasNext()).isTrue();
		verify(searchEngine).searchStores(eq(intent), eq(pageable), isNull(), isNull(),
			eq(SearchSort.RELEVANCE));
	}

	@Test
	@DisplayName("지표 — 2페이지 이후는 스크롤이므로 집계하지 않는다")
	void searchStores_doesNotRecordOnLaterPages() {
		// 무한스크롤이 페이지마다 검색 API를 부른다. 이걸 다 세면 "많이 스크롤한 검색어"가
		// "여러 사람이 찾은 검색어"보다 위로 올라와 실패 목록의 우선순위가 뒤집힌다.
		SearchIntent intent = new SearchIntent("천안시 서북구", "천안시 서북구", List.of("천안시", "서북구"),
			List.of("천안시"), List.of(), List.of("서북구"));
		PageRequest secondPage = PageRequest.of(1, 15);
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("천안시 서북구")).willReturn(intent);
		given(searchEngine.searchStores(intent, secondPage, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("천안시 서북구", null, null, SearchSort.RELEVANCE, secondPage);

		for (SearchOutcome outcome : SearchOutcome.values()) {
			assertThat(outcomeCount("search", outcome)).isZero();
		}
		verify(eventPublisher, never()).publishEvent(any(SearchMissEvent.class));
	}

	@Test
	@DisplayName("지표 — 첫 페이지 검색은 정상적으로 집계한다")
	void searchStores_recordsOnFirstPage() {
		SearchIntent intent = new SearchIntent("천안시 서북구", "천안시 서북구", List.of("천안시", "서북구"),
			List.of("천안시"), List.of(), List.of("서북구"));
		PageRequest firstPage = PageRequest.of(0, 15);
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("천안시 서북구")).willReturn(intent);
		given(searchEngine.searchStores(intent, firstPage, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("천안시 서북구", null, null, SearchSort.RELEVANCE, firstPage);

		assertThat(outcomeCount("search", SearchOutcome.MISMATCH_LOOSE)).isEqualTo(1.0);
		verify(eventPublisher).publishEvent(any(SearchMissEvent.class));
	}

	@Test
	@DisplayName("지표 — 폴백까지 돌려도 0건이면 empty로 집계한다")
	void searchStores_recordsEmptyOutcome() {
		SearchIntent intent = new SearchIntent("없는빵", "없는빵", List.of("없는빵"),
			List.of(), List.of(), List.of("없는빵"));
		PageRequest pageable = PageRequest.of(0, 15);
		given(storeSearchQueryParser.parse("없는빵")).willReturn(intent);
		given(storeSearchQueryParser.parseWithGluedTokenFallback("없는빵")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(StoreSearchResult.empty());

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
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("초코소금빵")).willReturn(primary);
		given(storeSearchQueryParser.parseWithGluedTokenFallback("초코소금빵")).willReturn(fallback);
		given(searchEngine.searchStores(primary, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(StoreSearchResult.empty());
		given(searchEngine.searchStores(fallback, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
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
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("성수동 소금빵")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, true));
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
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("성수동 소금빵")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
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
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("맘모스 베이커리")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("맘모스 베이커리", null, null, SearchSort.RELEVANCE, pageable);

		assertThat(outcomeCount("search", SearchOutcome.MISMATCH_LOOSE)).isEqualTo(1.0);
		assertThat(outcomeCount("search", SearchOutcome.MISMATCH)).isZero();
	}

	@Test
	@DisplayName("적재 — 실패한 검색어는 원문과 outcome을 담아 이벤트로 발행한다")
	void searchStores_publishesSearchMissEventOnFailure() {
		SearchIntent intent = new SearchIntent("맘모스 베이커리", "맘모스 베이커리", List.of("맘모스", "베이커리"),
			List.of(), List.of("맘모스"), List.of("베이커리"));
		PageRequest pageable = PageRequest.of(0, 15);
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("맘모스 베이커리")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, false));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("맘모스 베이커리", null, null, SearchSort.RELEVANCE, pageable);

		ArgumentCaptor<SearchMissEvent> captor = ArgumentCaptor.forClass(SearchMissEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().keyword()).isEqualTo("맘모스 베이커리");
		assertThat(captor.getValue().outcome()).isEqualTo(SearchOutcome.MISMATCH_LOOSE);
		assertThat(captor.getValue().occurredAt()).isNotNull();
	}

	@Test
	@DisplayName("적재 — 성공한 검색은 이벤트를 발행하지 않는다")
	void searchStores_doesNotPublishEventOnHit() {
		SearchIntent intent = new SearchIntent("성수동 소금빵", "성수동 소금빵", List.of("성수동", "소금빵"),
			List.of("성수동"), List.of("소금빵"), List.of());
		PageRequest pageable = PageRequest.of(0, 15);
		List<StoreSearchHit> hits = List.of(hit());
		given(storeSearchQueryParser.parse("성수동 소금빵")).willReturn(intent);
		given(searchEngine.searchStores(intent, pageable, null, null, SearchSort.RELEVANCE))
			.willReturn(new StoreSearchResult(hits, false, true));
		given(storeSearchAssembler.assemble(hits)).willReturn(List.of());

		service.searchStores("성수동 소금빵", null, null, SearchSort.RELEVANCE, pageable);

		verify(eventPublisher, never()).publishEvent(any(SearchMissEvent.class));
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
	void autocompleteStores_carriesRegionCoordinatesAndParentName() {
		// 같은 이름의 동네가 여럿이라("중동"은 열세 곳) 부모 이름 없이는 어느 곳인지 고를 수 없고,
		// 좌표·반경 없이는 고른 뒤 지도를 어디로 옮길지 알 수 없다.
		given(searchEngine.autocompleteStores("중동", 5)).willReturn(List.of(
			new StoreAutocompleteSuggestion("중동", AutocompleteSuggestionType.REGION, null,
				37.48784, 126.76819, 1500, "부천시"),
			new StoreAutocompleteSuggestion("중동", AutocompleteSuggestionType.REGION, null,
				35.15896, 129.17825, 1500, "해운대구"),
			new StoreAutocompleteSuggestion("중동제과", AutocompleteSuggestionType.STORE, 7L)));

		var result = service.autocompleteStores("중동", 5);

		assertThat(result).hasSize(3);
		assertThat(result.get(0).parentName()).isEqualTo("부천시");
		assertThat(result.get(0).centerLat()).isEqualTo(37.48784);
		assertThat(result.get(0).centerLng()).isEqualTo(126.76819);
		assertThat(result.get(0).radiusMeters()).isEqualTo(1500);
		assertThat(result.get(1).parentName()).isEqualTo("해운대구");
		// 가게 후보는 좌표를 갖지 않는다 — 지도를 옮길 자리가 아니라 가게 그 자체다
		assertThat(result.get(2).type()).isEqualTo(AutocompleteSuggestionType.STORE);
		assertThat(result.get(2).centerLat()).isNull();
		assertThat(result.get(2).parentName()).isNull();
	}

	@Test
	void autocompleteStores_deduplicatesByTypeAndKeywordAndMovesStoresToBottom() {
		given(searchEngine.autocompleteStores("소금", 3)).willReturn(List.of(
			new StoreAutocompleteSuggestion("소금집델리", AutocompleteSuggestionType.STORE, 1L),
			new StoreAutocompleteSuggestion("소금빵", AutocompleteSuggestionType.BREAD_TYPE, null),
			new StoreAutocompleteSuggestion("소금빵", AutocompleteSuggestionType.BREAD_TYPE, null),
			new StoreAutocompleteSuggestion("트러플 소금빵", AutocompleteSuggestionType.MENU, null),
			new StoreAutocompleteSuggestion("소금집델리", AutocompleteSuggestionType.STORE, 2L)));

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
		given(searchEngine.autocompleteStores("망원", 3)).willReturn(List.of(
			new StoreAutocompleteSuggestion("망원동 찐 초코 바게트", AutocompleteSuggestionType.MENU, null),
			new StoreAutocompleteSuggestion("파운드마켓 망원점", AutocompleteSuggestionType.STORE, 4619L),
			new StoreAutocompleteSuggestion("망원", AutocompleteSuggestionType.REGION, null)));

		var result = service.autocompleteStores("망원", 3);

		assertThat(result).hasSize(3);
		assertThat(result.get(0).keyword()).isEqualTo("망원");
		assertThat(result.get(0).type()).isEqualTo(AutocompleteSuggestionType.REGION);
		assertThat(result.get(1).type()).isEqualTo(AutocompleteSuggestionType.MENU);
		assertThat(result.get(2).type()).isEqualTo(AutocompleteSuggestionType.STORE);
	}

	@Test
	@DisplayName("types를 주면 그 타입만 남기고, 후보는 최대치(20)로 가져온다")
	void autocompleteStores_filtersByRequestedTypes() {
		given(searchEngine.autocompleteStores("어글리", 20)).willReturn(List.of(
			new StoreAutocompleteSuggestion("어글리 크림빵", AutocompleteSuggestionType.MENU, null),
			new StoreAutocompleteSuggestion("어글리쿠키", AutocompleteSuggestionType.MENU, null),
			new StoreAutocompleteSuggestion("어글리베이커리", AutocompleteSuggestionType.STORE, 7L),
			new StoreAutocompleteSuggestion("망원", AutocompleteSuggestionType.REGION, null)));

		var result = service.autocompleteStores("어글리", 3,
			List.of(AutocompleteSuggestionType.STORE, AutocompleteSuggestionType.REGION));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).keyword()).isEqualTo("망원");
		assertThat(result.get(0).type()).isEqualTo(AutocompleteSuggestionType.REGION);
		assertThat(result.get(1).keyword()).isEqualTo("어글리베이커리");
		assertThat(result.get(1).type()).isEqualTo(AutocompleteSuggestionType.STORE);
	}

	@Test
	@DisplayName("지표 — 자동완성 결과가 0건이면 empty로 집계한다")
	void autocompleteStores_recordsEmptyOutcome() {
		given(searchEngine.autocompleteStores("ㅁㄴㅇㄹ", 10)).willReturn(List.of());

		var result = service.autocompleteStores("ㅁㄴㅇㄹ", 10);

		assertThat(result).isEmpty();
		assertThat(outcomeCount("autocomplete", SearchOutcome.EMPTY)).isEqualTo(1.0);
	}

	@Test
	void autocompleteStores_returnsEmptyWhenQueryIsBlank() {
		var result = service.autocompleteStores("  ", 10);

		assertThat(result).isEmpty();
		verify(searchEngine, never()).autocompleteStores(any(), anyInt());
		// 빈 입력은 검색 시도가 아니므로 집계하지 않는다 (시계열은 사전 등록되어 있으므로 0)
		assertThat(outcomeCount("autocomplete", SearchOutcome.EMPTY)).isZero();
	}

	// ---- 입력 검증 (부하가 운영 RDB 에 직접 실리므로 비로그인 엔드포인트의 상한·범위를 서비스에서 막는다)

	@Test
	void searchStores_clampsPageSizeToMax() {
		SearchIntent intent = new SearchIntent("성심당", "성심당", List.of("성심당"), List.of(), List.of(), List.of("성심당"));
		given(storeSearchQueryParser.parse("성심당")).willReturn(intent);
		given(searchEngine.searchStores(eq(intent), any(), isNull(), isNull(), eq(SearchSort.RELEVANCE)))
			.willReturn(new StoreSearchResult(List.of(hit()), false, true));
		given(storeSearchAssembler.assemble(any())).willReturn(List.of());

		service.searchStores("성심당", null, null, SearchSort.RELEVANCE, PageRequest.of(0, 100_000));

		ArgumentCaptor<org.springframework.data.domain.Pageable> captor = ArgumentCaptor
			.forClass(org.springframework.data.domain.Pageable.class);
		verify(searchEngine).searchStores(eq(intent), captor.capture(), isNull(), isNull(), eq(SearchSort.RELEVANCE));
		assertThat(captor.getValue().getPageSize()).isEqualTo(StoreSearchService.MAX_SEARCH_PAGE_SIZE);
		assertThat(captor.getValue().getPageNumber()).isZero();
	}

	@Test
	void searchStores_rejectsOutOfRangeCoordinates() {
		// PostGIS 가 "Coordinate values are out of range" 로 500 을 내기 전에 400 으로 거른다
		assertThatThrownBy(
			() -> service.searchStores("성심당", 1000.0, 127.0, SearchSort.RELEVANCE, PageRequest.of(0, 15)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.searchStores("성심당", 37.5, 181.0, SearchSort.RELEVANCE, PageRequest.of(0, 15)))
			.isInstanceOf(IllegalArgumentException.class);
		verify(searchEngine, never()).searchStores(any(), any(), any(), any(), any());
	}

	@Test
	void searchStores_rejectsHalfCoordinates() {
		assertThatThrownBy(() -> service.searchStores("성심당", 37.5, null, SearchSort.RELEVANCE, PageRequest.of(0, 15)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- 검색어 유형 분류·판정 신뢰도

	@Test
	void classifyIntent_splitsByRegionAndNameTokens() {
		assertThat(StoreSearchService.classifyIntent(
			new SearchIntent("망원 브릭", "망원 브릭", List.of("망원", "브릭"), List.of("망원"), List.of(), List.of("브릭"))))
			.isEqualTo("REGION_AND_NAME");
		assertThat(StoreSearchService.classifyIntent(
			new SearchIntent("성수동", "성수동", List.of("성수동"), List.of("성수동"), List.of(), List.of())))
			.isEqualTo("REGION_ONLY");
		assertThat(StoreSearchService.classifyIntent(
			new SearchIntent("소금빵", "소금빵", List.of("소금빵"), List.of(), List.of("소금빵"), List.of())))
			.isEqualTo("NAME_ONLY");
		assertThat(StoreSearchService.classifyIntent(
			new SearchIntent("빵", "빵", List.of("빵"), List.of(), List.of(), List.of())))
			.isEqualTo("UNCLASSIFIED");
	}

	@Test
	void isFullyClassified_ignoresStopWordOnlyRemainingTokens() {
		// "성수동 빵집": 잔여 "빵집"은 불용어라 cleanQuery 에서 빠졌다 → 사전에 없는 말이 아니므로 완전 분류
		assertThat(StoreSearchService.isFullyClassified(
			new SearchIntent("성수동 빵집", "성수동", List.of("성수동", "빵집"), List.of("성수동"), List.of(), List.of("빵집"))))
			.isTrue();
		// "성수동 맘모스": 잔여 "맘모스"가 cleanQuery 에 살아 있다 → 사전 밖 말이 섞임
		assertThat(StoreSearchService.isFullyClassified(
			new SearchIntent("성수동 맘모스", "성수동 맘모스", List.of("성수동", "맘모스"), List.of("성수동"), List.of(),
				List.of("맘모스"))))
			.isFalse();
		// 지역도 빵도 없으면 분류 자체가 안 된 것
		assertThat(StoreSearchService.isFullyClassified(
			new SearchIntent("빵", "빵", List.of("빵"), List.of(), List.of(), List.of())))
			.isFalse();
	}
}
