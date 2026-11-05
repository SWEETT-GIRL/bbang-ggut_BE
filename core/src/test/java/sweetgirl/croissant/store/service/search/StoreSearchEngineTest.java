package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.store.config.StoreAutocompleteCacheProperties;
import sweetgirl.croissant.store.config.StoreSearchProperties;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.enums.MatchedField;
import sweetgirl.croissant.store.repository.search.SearchSqlQuery;
import sweetgirl.croissant.store.repository.search.StoreAutocompleteRow;
import sweetgirl.croissant.store.repository.search.StoreSearchJdbcRepository;
import sweetgirl.croissant.store.repository.search.StoreSearchRow;
import sweetgirl.croissant.store.repository.search.StoreSearchTokenCountRow;
import sweetgirl.croissant.store.service.search.cache.StoreAutocompleteCache;

@ExtendWith(MockitoExtension.class)
class StoreSearchEngineTest {

	@Mock
	private StoreSearchJdbcRepository storeSearchJdbcRepository;

	private StoreSearchEngine engine;

	@BeforeEach
	void setUp() {
		// SQL 빌더는 순수 로직이라 실제 구현을 쓴다 — 계획 → SQL 파라미터까지 함께 검증된다.
		// 캐시는 꺼 둔다(매번 DB) — 캐시 동작은 StoreAutocompleteCacheTest 가 따로 본다
		StoreAutocompleteCache disabledCache = new StoreAutocompleteCache(null, null,
			new StoreAutocompleteCacheProperties(), new SimpleMeterRegistry());
		engine = new StoreSearchEngine(new StoreSearchSqlBuilder(new StoreSearchProperties()),
			storeSearchJdbcRepository, disabledCache);
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
	@DisplayName("완화 ① — 지역으로 떨어진 토큰도 이름 토큰으로 보고 어순과 무관하게 찾는다")
	void searchStoresRelaxed_matchesAllTokensInNameRegardlessOfOrder() {
		// "성수 어니언" — 가게 이름은 "어니언 성수". '성수'가 지역으로 떨어지고 전체("성수어니언")는 이름에 없어 1차는 0건이다.
		SearchIntent intent = intent("성수 어니언", List.of("성수"), List.of(), List.of("어니언"));
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(row(5, 80, 0, 0, 0)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(intent),
			PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).search(captor.capture());
		// 지역 조건 없이 이름(구명) 토큰 교집합만
		assertThat(captor.getValue().sql()).doesNotContain("store_search_region", "address_normalized");
		assertThat(captor.getValue().sql()).contains("INTERSECT");
		assertThat(captor.getValue().params().getValue("name0")).isEqualTo("성수");
		assertThat(captor.getValue().params().getValue("name1")).isEqualTo("어니언");
		assertThat(relaxed).isPresent();
		assertThat(relaxed.get().stage()).isEqualTo(SearchFallbackStage.NAME_ANY_ORDER);
		assertThat(relaxed.get().keptToken()).isEmpty();
		assertThat(relaxed.get().result().hits()).extracting(hit -> hit.storeId()).containsExactly(5L);
		assertThat(relaxed.get().result().hits().get(0).matchedFields()).containsExactly(MatchedField.STORE_NAME);
		verify(storeSearchJdbcRepository, never()).countTokenCandidates(any());
	}

	@Test
	@DisplayName("완화 ① — 어순이 달라도 같은 계획이 된다")
	void toAnyOrderNamePlan_sameCandidatesForEitherOrder() {
		// 파서가 지역 토큰을 먼저 모으므로 "성수 어니언"과 "어니언 성수"의 이름 토큰 집합이 같다
		Optional<StoreSearchPlan> regionFirst = engine.toAnyOrderNamePlan(
			intent("성수 어니언", List.of("성수"), List.of(), List.of("어니언")));
		Optional<StoreSearchPlan> nameFirst = engine.toAnyOrderNamePlan(
			intent("어니언 성수", List.of("성수"), List.of(), List.of("어니언")));

		assertThat(regionFirst).isPresent();
		assertThat(regionFirst.get().regionTokens()).isEmpty();
		assertThat(regionFirst.get().nameTokens()).containsExactly("성수", "어니언");
		assertThat(nameFirst.get().nameTokens()).isEqualTo(regionFirst.get().nameTokens());
	}

	@Test
	@DisplayName("완화 ① — 지역 토큰이 없으면 1차와 같은 조건이라 다시 묻지 않는다")
	void toAnyOrderNamePlan_emptyWithoutRegionToken() {
		assertThat(engine.toAnyOrderNamePlan(intent("우드 브릭", List.of(), List.of(), List.of("우드", "브릭"))))
			.isEmpty();
		// 지역 토큰 하나뿐이면 이름 토큰으로 바꿔도 1차의 "이름에 검색어 포함" 가지와 같다
		assertThat(engine.toAnyOrderNamePlan(intent("성수", List.of("성수"), List.of(), List.of()))).isEmpty();
	}

	@Test
	@DisplayName("완화 ① — 붙여 쓴 토큰을 분해한 인텐트로도 한 번 더 찾는다")
	void searchStoresRelaxed_triesDecomposedIntentForAnyOrderMatch() {
		SearchIntent primary = intent("성수어니언", List.of(), List.of(), List.of("성수어니언"));
		SearchIntent decomposed = intent("성수어니언", List.of("성수"), List.of(), List.of("어니언"));
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(row(5, 80, 0, 0, 0)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(primary, decomposed),
			PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).search(captor.capture());
		assertThat(captor.getValue().params().getValue("name0")).isEqualTo("성수");
		assertThat(captor.getValue().params().getValue("name1")).isEqualTo("어니언");
		assertThat(relaxed.get().stage()).isEqualTo(SearchFallbackStage.NAME_ANY_ORDER);
	}

	@Test
	@DisplayName("완화 ② — 후보가 가장 적은 토큰 하나만 남겨 다시 찾는다")
	void searchStoresRelaxed_keepsMostSpecificToken() {
		// "사과당 망원 베이커리" — 망원에 지점이 없다. 사과당(3곳)이 베이커리(상한 초과)보다 구체적이다.
		SearchIntent intent = intent("사과당 망원 베이커리", List.of("망원"), List.of(), List.of("사과당", "베이커리"));
		given(storeSearchJdbcRepository.search(any()))
			.willReturn(List.of())
			.willReturn(List.of(row(11, 80, 0, 0, 0), row(12, 80, 0, 0, 0)));
		given(storeSearchJdbcRepository.countTokenCandidates(any())).willReturn(List.of(
			new StoreSearchTokenCountRow(0, 3),
			new StoreSearchTokenCountRow(1, StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES + 1)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(intent),
			PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> countCaptor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).countTokenCandidates(countCaptor.capture());
		// 지역 토큰(망원)은 남길 후보가 아니다 — 세지도 않는다
		assertThat(countCaptor.getValue().params().getValue("name0")).isEqualTo("사과당");
		assertThat(countCaptor.getValue().params().getValue("name1")).isEqualTo("베이커리");
		assertThat(countCaptor.getValue().params().hasValue("name2")).isFalse();
		assertThat(countCaptor.getValue().params().getValue("cap"))
			.isEqualTo(StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES + 1);
		ArgumentCaptor<SearchSqlQuery> searchCaptor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository, times(2)).search(searchCaptor.capture());
		SearchSqlQuery singleTokenQuery = searchCaptor.getAllValues().get(1);
		assertThat(singleTokenQuery.params().getValue("name0")).isEqualTo("사과당");
		assertThat(singleTokenQuery.params().getValue("whole")).isEqualTo("사과당");
		assertThat(singleTokenQuery.params().hasValue("name1")).isFalse();
		assertThat(singleTokenQuery.sql()).doesNotContain("store_search_region", "INTERSECT");
		// 남긴 토큰이 이름/구명의 맨 앞에 오는 가게만 — 후보 수를 센 SQL 과 같은 조건이다
		String prefixCondition = "s.name_normalized LIKE '%' || :name0 || '%' AND s.name_normalized LIKE :name0 || '%'";
		String aliasPrefixCondition = "a.alias_normalized LIKE '%' || :name0 || '%'"
			+ " AND a.alias_normalized LIKE :name0 || '%'";
		assertThat(singleTokenQuery.sql()).contains(prefixCondition, aliasPrefixCondition);
		assertThat(countCaptor.getValue().sql()).contains(prefixCondition, aliasPrefixCondition);
		assertThat(relaxed).isPresent();
		assertThat(relaxed.get().stage()).isEqualTo(SearchFallbackStage.SINGLE_TOKEN);
		assertThat(relaxed.get().keptToken()).isEqualTo("사과당");
		assertThat(relaxed.get().result().hits()).extracting(hit -> hit.storeId()).containsExactly(11L, 12L);
	}

	@Test
	@DisplayName("완화 ① — 어순 무관 이름 매칭은 접두로 좁히지 않는다(부분일치 그대로)")
	void searchStoresRelaxed_anyOrderPlanKeepsContainsMatch() {
		SearchIntent intent = intent("성수 어니언", List.of("성수"), List.of(), List.of("어니언"));
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(row(5, 80, 0, 0, 0)));

		engine.searchStoresRelaxed(List.of(intent), PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).search(captor.capture());
		assertThat(captor.getValue().sql()).doesNotContain("s.name_normalized LIKE :name0 || '%'\n",
			"a.alias_normalized LIKE :name0 || '%')");
		assertThat(engine.toAnyOrderNamePlan(intent).get().namePrefixOnly()).isFalse();
	}

	@Test
	@DisplayName("완화 ② — 남긴 토큰으로 시작하는 가게가 없으면(다른 단어의 일부로만 걸리면) 0건을 유지한다")
	void searchStoresRelaxed_staysEmptyWhenTokenOnlyMatchesInsideOtherWords() {
		// "버티 샌드위치" — '버티'는 "리버티베이글"의 일부일 뿐이다. 접두로 세면 후보 0 이라 폴백하지 않는다.
		// ("메가커피 첨단"→알파오메가커피센터, "타코메카 히카"→우연히카페, "Gs25 지제"→슬지제빵소 도 같은 경우)
		SearchIntent intent = intent("버티 샌드위치", List.of(), List.of("샌드위치"), List.of("버티"));
		given(storeSearchJdbcRepository.countTokenCandidates(any())).willReturn(List.of(
			new StoreSearchTokenCountRow(0, StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES + 1),
			new StoreSearchTokenCountRow(1, 0)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(intent),
			PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(relaxed).isEmpty();
		verify(storeSearchJdbcRepository, never()).search(any());
	}

	@Test
	@DisplayName("완화 ② — 숫자뿐인 토큰은 남기지 않는다")
	void selectMostSpecificToken_skipsNumericOnlyTokens() {
		// "396 베이커리"→한양도성1396, "양천로 489 상가"→카페489-11 처럼 이름 속 숫자에 걸리던 경우
		SearchIntent intent = intent("396 베이커리", List.of(), List.of(), List.of("396", "베이커리"));
		given(storeSearchJdbcRepository.countTokenCandidates(any())).willReturn(List.of(
			new StoreSearchTokenCountRow(0, StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES + 1)));

		assertThat(engine.selectMostSpecificToken(intent)).isEmpty();

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).countTokenCandidates(captor.capture());
		// 세는 대상은 "베이커리"뿐이다
		assertThat(captor.getValue().params().getValue("name0")).isEqualTo("베이커리");
		assertThat(captor.getValue().params().hasValue("name1")).isFalse();
	}

	@Test
	@DisplayName("완화 ② — 숫자뿐인 토큰만 남으면 후보를 세지도 않는다")
	void selectMostSpecificToken_emptyWhenOnlyNumericTokensRemain() {
		SearchIntent intent = intent("489 15", List.of(), List.of(), List.of("489", "15"));

		assertThat(engine.selectMostSpecificToken(intent)).isEmpty();
		verify(storeSearchJdbcRepository, never()).countTokenCandidates(any());
	}

	@Test
	@DisplayName("완화 ② — 숫자가 섞인 가게명 토큰은 남길 수 있다")
	void selectMostSpecificToken_keepsAlphanumericToken() {
		SearchIntent intent = intent("237베이커리 장기", List.of("장기"), List.of(), List.of("237베이커리"));
		given(storeSearchJdbcRepository.countTokenCandidates(any()))
			.willReturn(List.of(new StoreSearchTokenCountRow(0, 1)));

		assertThat(engine.selectMostSpecificToken(intent)).contains("237베이커리");
	}

	@Test
	@DisplayName("완화 ② — 넓은 토큰만 남으면 완화하지 않는다")
	void searchStoresRelaxed_doesNotKeepBroadToken() {
		SearchIntent intent = intent("없는가게 베이커리", List.of(), List.of(), List.of("없는가게", "베이커리"));
		given(storeSearchJdbcRepository.countTokenCandidates(any())).willReturn(List.of(
			new StoreSearchTokenCountRow(0, 0),
			new StoreSearchTokenCountRow(1, StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES + 1)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(intent),
			PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(relaxed).isEmpty();
		verify(storeSearchJdbcRepository, never()).search(any());
	}

	@Test
	@DisplayName("완화 ② — 지역명·불용어만 남는 검색어는 후보를 세지도 않는다")
	void selectMostSpecificToken_skipsRegionAndStopWordTokens() {
		// "덕계 맛집" — '맛집'은 불용어라 cleanQuery 에서 빠졌고 '덕계'는 지역이다
		SearchIntent intent = new SearchIntent("덕계 맛집", "덕계", List.of("덕계", "맛집"), List.of("덕계"), List.of(),
			List.of("맛집"));

		assertThat(engine.selectMostSpecificToken(intent)).isEmpty();
		verify(storeSearchJdbcRepository, never()).countTokenCandidates(any());
	}

	@Test
	@DisplayName("완화 ② — 불용어는 버리고 남은 가게명 토큰으로 찾는다")
	void selectMostSpecificToken_dropsStopWordAndKeepsNameToken() {
		SearchIntent intent = new SearchIntent("브릭베이글 맛집", "브릭베이글", List.of("브릭베이글", "맛집"), List.of(),
			List.of(), List.of("브릭베이글", "맛집"));
		given(storeSearchJdbcRepository.countTokenCandidates(any()))
			.willReturn(List.of(new StoreSearchTokenCountRow(0, 1)));

		assertThat(engine.selectMostSpecificToken(intent)).contains("브릭베이글");

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository).countTokenCandidates(captor.capture());
		assertThat(captor.getValue().params().hasValue("name1")).isFalse();
	}

	@Test
	@DisplayName("완화 ② — 토큰이 하나뿐이면 버릴 것이 없으니 다시 묻지 않는다")
	void selectMostSpecificToken_emptyForSingleTokenQuery() {
		assertThat(engine.selectMostSpecificToken(intent("대쟌", List.of(), List.of(), List.of("대쟌")))).isEmpty();
		verify(storeSearchJdbcRepository, never()).countTokenCandidates(any());
	}

	@Test
	@DisplayName("완화 ② — 후보 수가 같으면 긴 토큰, 그다음 앞선 토큰")
	void selectMostSpecificToken_breaksTiesByLengthThenPosition() {
		SearchIntent intent = intent("몽심 화이트리어 대구점 케이크",
			List.of(), List.of(), List.of("몽심", "화이트리어", "대구점", "케이크"));
		given(storeSearchJdbcRepository.countTokenCandidates(any())).willReturn(List.of(
			new StoreSearchTokenCountRow(0, 2),
			new StoreSearchTokenCountRow(1, 2),
			new StoreSearchTokenCountRow(2, 0),
			new StoreSearchTokenCountRow(3, StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES)));

		assertThat(engine.selectMostSpecificToken(intent)).contains("화이트리어");
	}

	@Test
	@DisplayName("완화 ② — 상한과 같은 후보 수까지는 남긴다")
	void selectMostSpecificToken_acceptsTokenAtThreshold() {
		SearchIntent intent = intent("없는말 케이크", List.of(), List.of(), List.of("없는말", "케이크"));
		given(storeSearchJdbcRepository.countTokenCandidates(any())).willReturn(List.of(
			new StoreSearchTokenCountRow(0, 0),
			new StoreSearchTokenCountRow(1, StoreSearchEngine.RELAXED_TOKEN_MAX_CANDIDATES)));

		assertThat(engine.selectMostSpecificToken(intent)).contains("케이크");
	}

	@Test
	@DisplayName("완화 — 2페이지 이후는 완화 없는 검색이 첫 페이지부터 0건일 때만 탄다")
	void searchStoresRelaxed_laterPage_skipsWhenUnrelaxedSearchHasResults() {
		// 결과가 15건인 검색의 2페이지(끝을 넘긴 요청)에 완화 결과가 이어 붙으면 안 된다
		SearchIntent intent = intent("성수 어니언", List.of("성수"), List.of(), List.of("어니언"));
		given(storeSearchJdbcRepository.search(any())).willReturn(List.of(row(1, 80, 0, 40, 0)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(intent),
			PageRequest.of(1, 15), null, null, SearchSort.RELEVANCE);

		assertThat(relaxed).isEmpty();
		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository, times(1)).search(captor.capture());
		// 첫 페이지에 결과가 있는지만 본다
		assertThat(captor.getValue().params().getValue("offset")).isEqualTo(0);
		assertThat(captor.getValue().sql()).contains("store_search_region");
	}

	@Test
	@DisplayName("완화 — 완화 결과의 2페이지는 같은 완화 결과셋을 이어 준다")
	void searchStoresRelaxed_laterPage_continuesRelaxedResultSet() {
		SearchIntent intent = intent("성수 어니언", List.of("성수"), List.of(), List.of("어니언"));
		given(storeSearchJdbcRepository.search(any()))
			// 첫 페이지 확인: 1차 0건 → 주소 0건
			.willReturn(List.of())
			.willReturn(List.of())
			.willReturn(List.of(row(31, 60, 0, 0, 0)));

		Optional<StoreSearchRelaxedResult> relaxed = engine.searchStoresRelaxed(List.of(intent),
			PageRequest.of(1, 15), null, null, SearchSort.RELEVANCE);

		ArgumentCaptor<SearchSqlQuery> captor = ArgumentCaptor.forClass(SearchSqlQuery.class);
		verify(storeSearchJdbcRepository, times(3)).search(captor.capture());
		assertThat(captor.getAllValues().get(2).params().getValue("offset")).isEqualTo(15);
		assertThat(relaxed.get().result().hits()).extracting(hit -> hit.storeId()).containsExactly(31L);
	}

	@Test
	void searchStores_propagatesDatabaseFailure() {
		SearchIntent intent = intent("성심당", List.of(), List.of(), List.of("성심당"));
		given(storeSearchJdbcRepository.search(any())).willThrow(new DataAccessResourceFailureException("db down"));

		assertThatThrownBy(() -> engine.searchStores(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE))
			.isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	void autocompleteStores_usesNormalizedQueryAsCacheKey() {
		// "성심 당"과 "성심당"이 같은 캐시를 나눠 쓴다 — 키는 정규화한 검색어
		StoreAutocompleteCache cache = mock(StoreAutocompleteCache.class);
		given(cache.getOrLoad(eq("성심당"), eq(10), any())).willReturn(List.of());
		StoreSearchEngine cachedEngine = new StoreSearchEngine(new StoreSearchSqlBuilder(new StoreSearchProperties()),
			storeSearchJdbcRepository, cache);

		cachedEngine.autocompleteStores("성심 당!", 10);

		verify(cache).getOrLoad(eq("성심당"), eq(10), any());
		verify(storeSearchJdbcRepository, never()).autocomplete(any());
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

	@Test
	void autocompleteStores_keepsPopularityForOrdering() {
		given(storeSearchJdbcRepository.autocomplete(any())).willReturn(List.of(
			new StoreAutocompleteRow("한작은빵집", "STORE", 1L, 0, 2),
			new StoreAutocompleteRow("한큰빵집", "STORE", 2L, 0, 30)));

		List<StoreAutocompleteSuggestion> suggestions = engine.autocompleteStores("한", 10);

		assertThat(suggestions).extracting(StoreAutocompleteSuggestion::keyword).containsExactly("한큰빵집", "한작은빵집");
		assertThat(suggestions.get(0).tier()).isZero();
		assertThat(suggestions.get(0).popularityScore()).isEqualTo(30);
	}
}
