package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.place;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.store;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.exception.NaverSearchErrorCode;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.searchmiss.entity.SearchMiss;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;
import sweetgirl.croissant.searchmiss.repository.SearchMissRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchOutcome;
import sweetgirl.croissant.store.service.search.StoreSearchProbe;
import sweetgirl.croissant.store.service.search.StoreSearchQueryParser;
import sweetgirl.croissant.store.service.search.StoreSearchService;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageServiceTest {

	private static final String KEYWORD = "런베뮤";
	private static final String OFFICIAL_NAME = "런던베이글뮤지엄";
	private static final StoreSearchProbe EMPTY = new StoreSearchProbe(SearchOutcome.EMPTY, List.of(), false);

	@Mock
	private SearchMissRepository searchMissRepository;

	@Mock
	private StoreRepository storeRepository;

	@Mock
	private StoreSearchService storeSearchService;

	@Mock
	private StoreSearchQueryParser storeSearchQueryParser;

	@Mock
	private SearchMissPlaceResolver placeResolver;

	@Mock
	private SearchMissKeywordInterpreter interpreter;

	@Mock
	private SearchMissTriageCommandService commandService;

	private SearchMissTriageProperties properties;
	private SearchMissTriageService triageService;

	@BeforeEach
	void setUp() {
		properties = new SearchMissTriageProperties();
		properties.setAutoApply(true);
		triageService = new SearchMissTriageService(searchMissRepository, storeRepository, storeSearchService,
			storeSearchQueryParser, placeResolver, interpreter, commandService, properties);
	}

	private static SearchIntent nameOnly(String keyword) {
		return new SearchIntent(keyword, keyword, List.of(keyword), List.of(), List.of(), List.of(keyword));
	}

	private static StoreSearchProbe hit(Long... storeIds) {
		return new StoreSearchProbe(SearchOutcome.HIT, List.of(storeIds), false);
	}

	private static PlaceResolution matchedActive(Store... stores) {
		return new PlaceResolution(List.of(place("100", OFFICIAL_NAME + " 안국점", "베이커리")), List.of(stores),
			List.of(), List.of(), List.of());
	}

	/** 사전에 없는 검색어 — 지금 검색해도 안 나온다 */
	private void givenUnknownKeyword(String keyword) {
		given(storeSearchService.probe(keyword, 1)).willReturn(EMPTY);
		givenNameOnly(keyword);
	}

	private void givenNameOnly(String keyword) {
		given(storeSearchQueryParser.parse(keyword)).willReturn(nameOnly(keyword));
		given(storeSearchQueryParser.parseWithGluedTokenFallback(keyword)).willReturn(nameOnly(keyword));
	}

	/** 지점 모으기 — 같은 이름 지점은 이미 매칭된 가게뿐 */
	private void givenNoOtherBranches(Store... stores) {
		List<Long> ids = java.util.Arrays.stream(stores).map(Store::getId).toList();
		given(storeSearchService.probe(eq(OFFICIAL_NAME), anyInt()))
			.willReturn(new StoreSearchProbe(SearchOutcome.HIT, ids, false));
		given(storeRepository.findAllById(ids)).willReturn(List.of(stores));
	}

	@Test
	@DisplayName("쓰레기 입력은 검색도 외부 호출도 없이 IGNORED 로 판정한다")
	void triage_junk() {
		TriageDecision decision = triageService.triage("ㅋㅋㅋ");

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.JUNK);
		assertThat(decision.resolvedStatus()).isEqualTo(SearchMissStatus.IGNORED);
		verifyNoInteractions(storeSearchService, placeResolver, interpreter);
	}

	@Test
	@DisplayName("지금 다시 검색해 나오면 이미 있는 것으로 닫는다")
	void triage_nowHit() {
		given(storeSearchService.probe(KEYWORD, 1)).willReturn(hit(5L));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.NOW_HIT);
		assertThat(decision.resolvedStatus()).isEqualTo(SearchMissStatus.ALREADY_EXISTS);
		assertThat(decision.matchedStoreId()).isEqualTo(5L);
		verifyNoInteractions(placeResolver, interpreter);
	}

	@Test
	@DisplayName("지역·빵 사전 단어로만 된 검색어는 외부 호출 없이 가게 아님으로 본다")
	void triage_dictionaryOnly() {
		SearchIntent intent = new SearchIntent("성수 소금빵", "성수 소금빵", List.of("성수", "소금빵"), List.of("성수"),
			List.of("소금빵"), List.of());
		given(storeSearchService.probe("성수 소금빵", 1)).willReturn(EMPTY);
		given(storeSearchQueryParser.parse("성수 소금빵")).willReturn(intent);

		TriageDecision decision = triageService.triage("성수 소금빵");

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.NOT_A_STORE);
		assertThat(decision.resolvedStatus()).isNull();
		verifyNoInteractions(placeResolver, interpreter);
	}

	@Test
	@DisplayName("네이버 장소가 우리 가게면 지점까지 별칭을 넣고, 다시 검색해 나오면 ALREADY_EXISTS")
	void triage_aliasFromNaverPlace() {
		Store matched = store(1L, OFFICIAL_NAME + " 안국점", StoreStatus.ACTIVE);
		Store sibling = store(2L, OFFICIAL_NAME + " 잠실점", StoreStatus.ACTIVE);
		givenNameOnly(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(matchedActive(matched));
		given(storeSearchService.probe(eq(OFFICIAL_NAME.toLowerCase()), anyInt())).willReturn(hit(1L, 2L));
		given(storeRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(matched, sibling));
		given(commandService.addAliases(KEYWORD, List.of(1L, 2L))).willReturn(List.of(10L, 11L));
		given(storeSearchService.probe(KEYWORD, 1)).willReturn(EMPTY, hit(1L));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.ALIAS_RESOLVED);
		assertThat(decision.resolvedStatus()).isEqualTo(SearchMissStatus.ALREADY_EXISTS);
		assertThat(decision.matchedStoreId()).isEqualTo(1L);
		assertThat(decision.evidence()).containsEntry("aliasStoreIds", List.of(1L, 2L));
		verify(commandService, never()).removeAliases(any());
		verifyNoInteractions(interpreter);
	}

	@Test
	@DisplayName("별칭을 넣어도 검색에 안 나오면 넣은 별칭을 되돌리고 사람이 보게 둔다")
	void triage_aliasRolledBackWhenStillMissing() {
		Store matched = store(1L, OFFICIAL_NAME, StoreStatus.ACTIVE);
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(matchedActive(matched));
		givenNoOtherBranches(matched);
		given(commandService.addAliases(KEYWORD, List.of(1L))).willReturn(List.of(10L));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		assertThat(decision.resolvedStatus()).isNull();
		verify(commandService).removeAliases(List.of(10L));
	}

	@Test
	@DisplayName("shadow mode 면 판정만 하고 별칭은 넣지 않는다")
	void triage_shadowModeDoesNotWrite() {
		properties.setAutoApply(false);
		Store matched = store(1L, OFFICIAL_NAME, StoreStatus.ACTIVE);
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(matchedActive(matched));
		givenNoOtherBranches(matched);

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.ALIAS_RESOLVED);
		verify(commandService, never()).addAliases(anyString(), any());
	}

	@Test
	@DisplayName("지역·빵 단어가 섞인 검색어는 가게를 찾았어도 별칭으로 넣지 않는다 — 일반어가 별칭이 되면 검색 전체가 오염된다")
	void triage_aliasNotAllowedForDictionaryWords() {
		String keyword = "안국 런베뮤";
		SearchIntent intent = new SearchIntent(keyword, keyword, List.of("안국", "런베뮤"), List.of("안국"), List.of(),
			List.of("런베뮤"));
		Store matched = store(1L, OFFICIAL_NAME, StoreStatus.ACTIVE);
		given(storeSearchService.probe(keyword, 1)).willReturn(EMPTY);
		given(storeSearchQueryParser.parse(keyword)).willReturn(intent);
		given(placeResolver.resolve(eq(keyword), any())).willReturn(matchedActive(matched));

		TriageDecision decision = triageService.triage(keyword);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		assertThat(decision.matchedStoreId()).isEqualTo(1L);
		verify(commandService, never()).addAliases(anyString(), any());
	}

	@Test
	@DisplayName("지점이 상한을 넘으면 별칭을 넣지 않고 사람이 본다")
	void triage_tooManyBranches() {
		Store matched = store(1L, OFFICIAL_NAME, StoreStatus.ACTIVE);
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(matchedActive(matched));
		given(storeSearchService.probe(eq(OFFICIAL_NAME.toLowerCase()), anyInt()))
			.willReturn(new StoreSearchProbe(SearchOutcome.HIT, List.of(1L), true));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		verify(commandService, never()).addAliases(anyString(), any());
	}

	@Test
	@DisplayName("삭제된 가게와만 일치하면 다시 등록할지 사람이 본다")
	void triage_existsButDeleted() {
		Store deleted = store(3L, OFFICIAL_NAME, StoreStatus.DELETED);
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(new PlaceResolution(
			List.of(place("100", OFFICIAL_NAME, "베이커리")), List.of(), List.of(deleted), List.of(), List.of()));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.EXISTS_BUT_DELETED);
		assertThat(decision.matchedStoreId()).isEqualTo(3L);
		assertThat(decision.resolvedStatus()).isNull();
	}

	@Test
	@DisplayName("우리 DB 에 없는 빵집이면 신규 가게 후보로 남긴다 — 등록은 사람이 한다")
	void triage_newStoreCandidate() {
		givenUnknownKeyword("새빵집");
		given(placeResolver.resolve(eq("새빵집"), any())).willReturn(new PlaceResolution(
			List.of(place("200", "새빵집", "베이커리")), List.of(), List.of(), List.of(),
			List.of(place("200", "새빵집", "베이커리"))));

		TriageDecision decision = triageService.triage("새빵집");

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.NEW_STORE_CANDIDATE);
		assertThat(decision.resolvedStatus()).isNull();
		assertThat(decision.evidence()).containsKey("newStoreCandidates");
		verifyNoInteractions(interpreter);
	}

	@Test
	@DisplayName("지역검색에 장소가 없으면 블로그로 정식 이름을 풀고, 그 이름으로 우리 가게를 찾아 별칭을 넣는다")
	void triage_aliasFromBlogInterpretation() {
		Store matched = store(1L, OFFICIAL_NAME + " 안국점", StoreStatus.ACTIVE);
		givenNameOnly(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret(KEYWORD)).willReturn(KeywordInterpretation.store(OFFICIAL_NAME, List.of("l1")));
		given(storeSearchService.probe(OFFICIAL_NAME, properties.getMaxAliasStores())).willReturn(hit(1L));
		given(storeRepository.findAllById(List.of(1L))).willReturn(List.of(matched));
		given(commandService.addAliases(KEYWORD, List.of(1L))).willReturn(List.of(10L));
		given(storeSearchService.probe(KEYWORD, 1)).willReturn(EMPTY, hit(1L));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.ALIAS_RESOLVED);
		assertThat(decision.evidence()).containsKey("interpretation");
		assertThat(decision.evidence().get("steps")).asInstanceOf(InstanceOfAssertFactories.LIST).contains("BLOG_LLM",
			"OFFICIAL_NAME_SEARCH");
	}

	@Test
	@DisplayName("정식 이름으로도 우리 검색에 없으면 그 이름으로 네이버 지역검색을 한 번 더 해 신규 후보를 찾는다")
	void triage_blogInterpretationThenNaverAgain() {
		givenUnknownKeyword("새빵");
		given(placeResolver.resolve(eq("새빵"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("새빵")).willReturn(KeywordInterpretation.store("새빵공방", List.of("l1")));
		given(storeSearchService.probe("새빵공방", properties.getMaxAliasStores())).willReturn(EMPTY);
		given(placeResolver.resolve(eq("새빵공방"), any())).willReturn(new PlaceResolution(
			List.of(place("200", "새빵공방", "베이커리")), List.of(), List.of(), List.of(),
			List.of(place("200", "새빵공방", "베이커리"))));

		TriageDecision decision = triageService.triage("새빵");

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.NEW_STORE_CANDIDATE);
	}

	@Test
	@DisplayName("블로그 해석이 메뉴·지역이면 가게 아님")
	void triage_blogNotAStore() {
		givenUnknownKeyword("두쫀쿠");
		given(placeResolver.resolve(eq("두쫀쿠"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("두쫀쿠")).willReturn(KeywordInterpretation.notAStore("블로그 해석: MENU"));

		TriageDecision decision = triageService.triage("두쫀쿠");

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.NOT_A_STORE);
	}

	@Test
	@DisplayName("블로그 해석이 검증을 못 통과하면 사람이 본다")
	void triage_blogUnresolved() {
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret(KEYWORD)).willReturn(KeywordInterpretation.unresolved("근거 부족"));

		TriageDecision decision = triageService.triage(KEYWORD);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		assertThat(decision.evidence()).containsEntry("reason", "근거 부족");
	}

	@Test
	@DisplayName("배치는 판정을 기록하고, 한 행의 실패는 건너뛰며 판정별 건수를 센다")
	void triageNextBatch_recordsAndSkipsFailures() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
		SearchMiss junk = SearchMiss.firstOccurrence("ㅋㅋ", "ㅋㅋㅋ", SearchOutcome.EMPTY, now);
		SearchMiss broken = SearchMiss.firstOccurrence("런베뮤", KEYWORD, SearchOutcome.EMPTY, now);
		ReflectionTestUtils.setField(junk, "id", 1L);
		ReflectionTestUtils.setField(broken, "id", 2L);
		given(searchMissRepository.findTriageCandidates(any(Pageable.class))).willReturn(List.of(junk, broken));
		given(storeSearchService.probe(KEYWORD, 1)).willThrow(new IllegalStateException("DB 끊김"));

		SearchMissTriageBatchResult result = triageService.triageNextBatch();

		assertThat(result.total()).isEqualTo(2);
		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.verdicts()).containsEntry(SearchMissVerdict.JUNK, 1);
		ArgumentCaptor<TriageDecision> captor = ArgumentCaptor.forClass(TriageDecision.class);
		verify(commandService).recordDecision(eq(1L), captor.capture(), eq(true), any(LocalDateTime.class));
		assertThat(captor.getValue().verdict()).isEqualTo(SearchMissVerdict.JUNK);
		verify(commandService, never()).recordDecision(eq(2L), any(), anyBoolean(), any());
	}

	@Test
	@DisplayName("네이버 한도 초과는 다시 던져 스케줄러가 쉬게 한다")
	void triageNextBatch_rethrowsNaverRateLimit() {
		SearchMiss miss = SearchMiss.firstOccurrence("런베뮤", KEYWORD, SearchOutcome.EMPTY, LocalDateTime.now());
		given(searchMissRepository.findTriageCandidates(any(Pageable.class))).willReturn(List.of(miss));
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any()))
			.willThrow(new NaverSearchException(NaverSearchErrorCode.RATE_LIMITED));

		assertThatThrownBy(() -> triageService.triageNextBatch()).isInstanceOf(NaverSearchException.class);
	}

	@Test
	@DisplayName("LLM 한도 초과도 다시 던진다")
	void triageNextBatch_rethrowsLlmRateLimit() {
		SearchMiss miss = SearchMiss.firstOccurrence("런베뮤", KEYWORD, SearchOutcome.EMPTY, LocalDateTime.now());
		given(searchMissRepository.findTriageCandidates(any(Pageable.class))).willReturn(List.of(miss));
		givenUnknownKeyword(KEYWORD);
		given(placeResolver.resolve(eq(KEYWORD), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret(KEYWORD)).willThrow(new LlmException(LlmErrorCode.RATE_LIMITED));

		assertThatThrownBy(() -> triageService.triageNextBatch()).isInstanceOf(LlmException.class);
	}
}
