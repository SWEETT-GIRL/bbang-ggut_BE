package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.store.config.StoreSearchProperties;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.enums.MatchedField;
import sweetgirl.croissant.store.repository.search.SearchSqlQuery;
import sweetgirl.croissant.store.repository.search.StoreAutocompleteRow;
import sweetgirl.croissant.store.repository.search.StoreSearchJdbcRepository;
import sweetgirl.croissant.store.repository.search.StoreSearchRow;

@ExtendWith(MockitoExtension.class)
class StoreSearchEngineTest {

	@Mock
	private StoreSearchJdbcRepository storeSearchJdbcRepository;

	private StoreSearchEngine engine;

	@BeforeEach
	void setUp() {
		// SQL 빌더는 순수 로직이라 실제 구현을 쓴다 — 계획 → SQL 파라미터까지 함께 검증된다.
		engine = new StoreSearchEngine(new StoreSearchSqlBuilder(new StoreSearchProperties()),
			storeSearchJdbcRepository);
	}

	private static SearchIntent intent(String raw, List<String> regions, List<String> breads, List<String> remaining) {
		return new SearchIntent(raw, raw, List.of(raw.split(" ")), regions, breads, remaining);
	}

	private static StoreSearchRow row(long id, double nameScore, double aliasScore, double regionScore,
			double addressScore) {
		return new StoreSearchRow(id, 10.0, 0.0, nameScore, aliasScore, regionScore, addressScore, null);
	}

	@Test
	void toPlan_normalizesTokensAndRoutesBreadAndRemainingToNameTokens() {
		SearchIntent intent = new SearchIntent("망원 브릭 베이글 점", "망원 브릭 베이글 점",
			List.of("망원", "브릭", "베이글", "점"), List.of("망원"), List.of("베이글"), List.of("Brick!", "점", "베이글"));

		StoreSearchPlan plan = engine.toPlan(intent, false);

		assertThat(plan.regionTokens()).containsExactly("망원");
		// 빵 토큰 + 잔여 토큰 → 이름 토큰. 정규화(소문자·특수문자 제거)·중복 제거·1글자 제외.
		assertThat(plan.nameTokens()).containsExactly("베이글", "brick");
		assertThat(plan.wholeQuery()).isEqualTo("망원브릭베이글점");
		assertThat(plan.addressFallback()).isFalse();
	}

	@Test
	void searchStores_returnsEmptyWithoutQueryWhenNothingSearchable() {
		SearchIntent blank = new SearchIntent("", "", List.of(), List.of(), List.of(), List.of());

		StoreSearchResult result = engine.searchStores(blank, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(result.hits()).isEmpty();
		verify(storeSearchJdbcRepository, never()).search(any());
	}

	@Test
	void searchStores_fetchesOneExtraRowToDetermineHasNext() {
		SearchIntent intent = intent("성심당", List.of(), List.of(), List.of("성심당"));
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(
			row(1, 100, 0, 0, 0), row(2, 60, 0, 0, 0), row(3, 60, 0, 0, 0)));

		StoreSearchResult result = engine.searchStores(intent, PageRequest.of(0, 2), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).search(captor.capture());
		assertThat(captor.getValue().params().getValue("limit")).isEqualTo(3);
		assertThat(result.hits()).extracting(hit -> hit.storeId()).containsExactly(1L, 2L);
		assertThat(result.hasNext()).isTrue();
		assertThat(result.queryCovered()).isTrue();
		assertThat(result.hits().get(0).matchedFields()).containsExactly(MatchedField.STORE_NAME);
		assertThat(result.hits().get(0).matchedMenuIds()).isEmpty();
	}

	@Test
	void searchStores_marksRegionAndNameMatchedFields() {
		SearchIntent intent = intent("망원 브릭", List.of("망원"), List.of(), List.of("브릭"));
		// 이름 토큰이 있는 계획의 후보는 구성상 이름이 걸렸다 — 이름 점수 0 이어도 STORE_NAME
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(row(7, 0, 0, 40, 0)));

		StoreSearchResult result = engine.searchStores(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(result.hits().get(0).matchedFields())
			.containsExactlyInAnyOrder(MatchedField.STORE_NAME, MatchedField.REGION);
		assertThat(result.queryCovered()).isTrue();
	}

	@Test
	void searchStores_regionOnly_coveredOnlyWhenRegionOrNameMatched() {
		SearchIntent intent = intent("성수동", List.of("성수동"), List.of(), List.of());
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(row(1, 0, 0, 0, 0)));

		StoreSearchResult result = engine.searchStores(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(result.hits().get(0).matchedFields()).isEmpty();
		assertThat(result.queryCovered()).isFalse();
	}

	@Test
	void searchStores_fallsBackToAddressWhenFirstPageIsEmpty() {
		SearchIntent intent = intent("천안시 서북구", List.of("천안시"), List.of(), List.of("서북구"));
		given(storeSearchJdbcRepository.search(any()))
			.willReturn(List.of())
			.willReturn(List.of(row(9, 0, 0, 0, 20)));

		StoreSearchResult result = engine.searchStores(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository, times(2)).search(captor.capture());
		assertThat(captor.getAllValues().get(1).sql()).contains("address_normalized");
		assertThat(result.hits()).extracting(hit -> hit.storeId()).containsExactly(9L);
		assertThat(result.hits().get(0).matchedFields()).containsExactly(MatchedField.REGION);
		assertThat(result.queryCovered()).isTrue();
	}

	@Test
	void searchStores_fallsBackToAddressOnLaterPagesToo() {
		// 1차가 0건인 검색어는 0페이지가 폴백 결과로 hasNext=true 를 줬으므로, 2페이지도 같은 폴백 결과셋을 이어야 한다.
		SearchIntent intent = intent("천안시 서북구", List.of("천안시"), List.of(), List.of("서북구"));
		given(storeSearchJdbcRepository.search(any()))
			.willReturn(List.of())
			.willReturn(List.of(row(21, 0, 0, 0, 20)));

		StoreSearchResult result = engine.searchStores(intent, PageRequest.of(1, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository, times(2)).search(captor.capture());
		assertThat(captor.getAllValues().get(1).sql()).contains("address_normalized");
		assertThat(captor.getAllValues().get(1).params().getValue("offset")).isEqualTo(15);
		assertThat(result.hits()).extracting(hit -> hit.storeId()).containsExactly(21L);
	}

	@Test
	void searchStores_propagatesDatabaseFailure() {
		SearchIntent intent = intent("성심당", List.of(), List.of(), List.of("성심당"));
		given(storeSearchJdbcRepository.search(any())).willThrow(new DataAccessResourceFailureException("db down"));

		assertThatThrownBy(() -> engine.searchStores(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE))
			.isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	void autocompleteStores_returnsEmptyForBlankQuery() {
		assertThat(engine.autocompleteStores("  !! ", 10)).isEmpty();
		verify(storeSearchJdbcRepository, never()).autocomplete(any());
	}

	@Test
	void autocompleteStores_sortsPrefixFirstThenPopularityAndMapsTypes() {
		given(storeSearchJdbcRepository.autocomplete(any())).willReturn(List.of(
			new StoreAutocompleteRow("제일성심당", "STORE", 3L, 1, 500),
			new StoreAutocompleteRow("성심당 본점", "STORE", 1L, 0, 149),
			new StoreAutocompleteRow("성심당", "REGION", null, 0, 0),
			new StoreAutocompleteRow("성심당 DCC점", "STORE", 2L, 0, 30)));

		List<StoreAutocompleteSuggestion> suggestions = engine.autocompleteStores("성심", 10);

		assertThat(suggestions).extracting(StoreAutocompleteSuggestion::keyword)
			.containsExactly("성심당 본점", "성심당 DCC점", "성심당", "제일성심당");
		assertThat(suggestions.get(2).type()).isEqualTo(AutocompleteSuggestionType.REGION);
		assertThat(suggestions.get(2).storeId()).isNull();
		assertThat(suggestions.get(0).storeId()).isEqualTo(1L);
	}
}
