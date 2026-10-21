package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.place;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.store;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

	private static final Long MISS_ID = 77L;
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
		properties.setAutoApplyVerdicts(EnumSet.of(SearchMissVerdict.CORRECTED));
		triageService = new SearchMissTriageService(searchMissRepository, storeRepository, storeSearchService,
			storeSearchQueryParser, placeResolver, interpreter, commandService, properties);
	}

	private static SearchIntent nameOnly(String keyword) {
		return new SearchIntent(keyword, keyword, List.of(keyword), List.of(), List.of(), List.of(keyword));
	}

	private static StoreSearchProbe hit(Long... storeIds) {
		return new StoreSearchProbe(SearchOutcome.HIT, List.of(storeIds), false);
	}

	/** 네이버 장소가 이 가게들과 같은 곳으로 확인됐다. 스텁 안에서 목을 부르지 않게 장소 이름은 고정 값이다 */
	private static PlaceResolution matched(Store... stores) {
		return new PlaceResolution(List.of(place("100", "네이버 장소", "베이커리")), List.of(stores),
			List.of(), List.of(), List.of());
	}

	/** 사전에 없는 검색어 — 파서는 지역·빵 단어를 못 찾는다 */
	private void givenNameOnly(String keyword) {
		given(storeSearchQueryParser.parse(keyword)).willReturn(nameOnly(keyword));
		given(storeSearchQueryParser.parseWithGluedTokenFallback(keyword)).willReturn(nameOnly(keyword));
	}

	/** 지금 검색해도 안 나오는 사전 밖 검색어 */
	private void givenUnknownKeyword(String keyword) {
		given(storeSearchService.probe(keyword, 1)).willReturn(EMPTY);
		givenNameOnly(keyword);
	}

	/** 블로그로 정식 이름을 풀었고, 그 이름으로 우리 검색에서 이 가게들이 나온다 */
	private void givenBlogResolvesTo(String keyword, String officialName, Store... stores) {
		given(placeResolver.resolve(eq(keyword), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret(keyword)).willReturn(KeywordInterpretation.store(officialName, List.of("l1")));
		List<Long> ids = Arrays.stream(stores).map(Store::getId).toList();
		given(storeSearchService.probe(officialName, properties.getNameProbeSize()))
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
	@DisplayName("이메일·전화번호가 든 검색어는 개인정보로 보고 외부로 보내지 않는다")
	void triage_personalInfo() {
		TriageDecision decision = triageService.triage("hohaho2514@gmail.com");

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.JUNK);
		assertThat(decision.evidence()).containsEntry("reason", "개인정보(이메일·전화번호)");
		verifyNoInteractions(storeSearchService, placeResolver, interpreter);
	}

	@Test
	@DisplayName("지금 다시 검색해 나오면 이미 있는 것으로 닫는다")
	void triage_nowHit() {
		given(storeSearchService.probe("반암스테르담", 1)).willReturn(hit(5L));

		TriageDecision decision = triageService.triage("반암스테르담");

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
		verifyNoInteractions(placeResolver, interpreter);
	}

	@Test
	@DisplayName("네이버 장소가 우리 가게 하나면 지점명 뗀 이름으로 교정을 넣고, 다시 검색해 나오면 ALREADY_EXISTS")
	void triage_correctsToSingleStore() {
		Store store = store(1L, "블랑제리코팡 본점", StoreStatus.ACTIVE);
		givenNameOnly("블랑젤리코팡");
		given(placeResolver.resolve(eq("블랑젤리코팡"), any())).willReturn(matched(store));
		given(commandService.addCorrection("블랑젤리코팡", "블랑제리코팡", MISS_ID)).willReturn(Optional.of(10L));
		given(storeSearchService.probe("블랑젤리코팡", 1)).willReturn(EMPTY, hit(1L));

		TriageDecision decision = triageService.triage("블랑젤리코팡", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.CORRECTED);
		assertThat(decision.resolvedStatus()).isEqualTo(SearchMissStatus.ALREADY_EXISTS);
		assertThat(decision.matchedStoreId()).isEqualTo(1L);
		assertThat(decision.evidence()).containsEntry("correctionTarget", "블랑제리코팡")
			.containsEntry("correctionId", 10L);
		verify(commandService, never()).removeCorrection(anyLong());
		verifyNoInteractions(interpreter);
	}

	@Test
	@DisplayName("교정해도 검색에 안 나오면 넣은 교정을 되돌리고 사람이 보게 둔다")
	void triage_rollsBackCorrection() {
		Store store = store(1L, "블랑제리코팡", StoreStatus.ACTIVE);
		givenUnknownKeyword("블랑젤리코팡");
		given(placeResolver.resolve(eq("블랑젤리코팡"), any())).willReturn(matched(store));
		given(commandService.addCorrection(anyString(), anyString(), any())).willReturn(Optional.of(10L));

		TriageDecision decision = triageService.triage("블랑젤리코팡", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		verify(commandService).removeCorrection(10L);
	}

	@Test
	@DisplayName("같은 검색어 교정이 이미 있으면 다시 넣지 않고 사람이 보게 둔다")
	void triage_existingCorrection() {
		Store store = store(1L, "블랑제리코팡", StoreStatus.ACTIVE);
		givenUnknownKeyword("블랑젤리코팡");
		given(placeResolver.resolve(eq("블랑젤리코팡"), any())).willReturn(matched(store));
		given(commandService.addCorrection(anyString(), anyString(), any())).willReturn(Optional.empty());

		TriageDecision decision = triageService.triage("블랑젤리코팡", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		verify(commandService, never()).removeCorrection(anyLong());
	}

	@Test
	@DisplayName("교정이 자동 적용 대상이 아니면(shadow) 판정과 교정 대상만 남기고 넣지 않는다")
	void triage_shadowDoesNotWrite() {
		properties.setAutoApplyVerdicts(EnumSet.noneOf(SearchMissVerdict.class));
		Store store = store(1L, "블랑제리코팡", StoreStatus.ACTIVE);
		givenUnknownKeyword("블랑젤리코팡");
		given(placeResolver.resolve(eq("블랑젤리코팡"), any())).willReturn(matched(store));

		TriageDecision decision = triageService.triage("블랑젤리코팡", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.CORRECTED);
		assertThat(decision.evidence()).containsEntry("correctionTarget", "블랑제리코팡");
		verify(commandService, never()).addCorrection(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("지역 단어가 섞인 검색어는 가게를 찾았어도 교정하지 않는다 — 교정하면 지역 조건이 사라진다")
	void triage_noCorrectionWithRegion() {
		String keyword = "어니언 성수";
		SearchIntent intent = new SearchIntent(keyword, keyword, List.of("어니언", "성수"), List.of("성수"), List.of(),
			List.of("어니언"));
		Store store = store(1L, "어니언 성수", StoreStatus.ACTIVE);
		given(storeSearchService.probe(keyword, 1)).willReturn(EMPTY);
		given(storeSearchQueryParser.parse(keyword)).willReturn(intent);
		given(placeResolver.resolve(eq(keyword), any())).willReturn(matched(store));

		TriageDecision decision = triageService.triage(keyword, MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		assertThat(decision.matchedStoreId()).isEqualTo(1L);
		verify(commandService, never()).addCorrection(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("가게 이름이 여럿이어도 검색어가 그 공통 앞부분(브랜드)의 오타면 브랜드로 교정한다 — 뚜쥬르 → 뚜쥬루")
	void triage_correctsToBrandPrefix() {
		Store bakery = store(1L, "뚜쥬루 빵돌가마마을", StoreStatus.ACTIVE);
		Store patisserie = store(2L, "뚜쥬루과자점", StoreStatus.ACTIVE);
		givenNameOnly("뚜쥬르");
		given(placeResolver.resolve(eq("뚜쥬르"), any())).willReturn(matched(bakery, patisserie));
		given(commandService.addCorrection("뚜쥬르", "뚜쥬루", MISS_ID)).willReturn(Optional.of(11L));
		given(storeSearchService.probe("뚜쥬르", 1)).willReturn(EMPTY, hit(1L, 2L));

		TriageDecision decision = triageService.triage("뚜쥬르", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.CORRECTED);
		assertThat(decision.evidence()).containsEntry("correctionTarget", "뚜쥬루");
	}

	@Test
	@DisplayName("블로그가 브랜드 이름을 주고 검색어가 그 오타면 브랜드로 교정한다 — 서심당 → 성심당")
	void triage_correctsToBlogBrand() {
		givenUnknownKeyword("서심당");
		givenBlogResolvesTo("서심당", "성심당", store(1L, "성심당 본점", StoreStatus.ACTIVE),
			store(2L, "성심당샌드위치정거장", StoreStatus.ACTIVE));
		given(commandService.addCorrection("서심당", "성심당", MISS_ID)).willReturn(Optional.of(12L));
		given(storeSearchService.probe("서심당", 1)).willReturn(EMPTY, hit(1L));

		TriageDecision decision = triageService.triage("서심당", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.CORRECTED);
		assertThat(decision.evidence()).containsEntry("correctionTarget", "성심당");
		assertThat(decision.evidence().get("steps")).asInstanceOf(InstanceOfAssertFactories.LIST)
			.contains("BLOG_LLM", "OFFICIAL_NAME_SEARCH");
	}

	@Test
	@DisplayName("특정 매장을 브랜드로 뭉뚱그린 경우는 교정하지 않는다 — 샌드위치 정류장 → 성심당 은 성심당 전체가 나와 틀린다")
	void triage_rejectsBroadBrand() {
		givenUnknownKeyword("샌드위치 정류장");
		givenBlogResolvesTo("샌드위치 정류장", "성심당", store(1L, "성심당 본점", StoreStatus.ACTIVE),
			store(2L, "성심당샌드위치정거장", StoreStatus.ACTIVE));

		TriageDecision decision = triageService.triage("샌드위치 정류장", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
		assertThat((String)decision.evidence().get("reason")).startsWith("검색어가 서로 다른 가게 여럿과 일치");
		verify(commandService, never()).addCorrection(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("관련 장소가 빵집이 아닌 것뿐이면 결론 내지 않고 블로그 단계로 넘긴다 — 뚜쥬르 전기차충전소")
	void triage_nonBakeryPlacesFallThroughToBlog() {
		givenUnknownKeyword("뚜쥬르");
		given(placeResolver.resolve(eq("뚜쥬르"), any())).willReturn(new PlaceResolution(
			List.of(place(null, "뚜쥬르 빵돌가마마을 전기차충전소", "교통,운수서비스>전기차충전소")), List.of(), List.of(),
			List.of(), List.of()));
		given(interpreter.interpret("뚜쥬르")).willReturn(KeywordInterpretation.unresolved("블로그로 판단 불가"));

		TriageDecision decision = triageService.triage("뚜쥬르", MISS_ID);

		verify(interpreter).interpret("뚜쥬르");
		assertThat(decision.evidence()).containsEntry("nonBakeryPlacesOnly", true);
	}

	@Test
	@DisplayName("네이버 장소도 블로그 글도 없는, 한글 없는 문자열은 쓰레기로 본다")
	void triage_randomLatinIsJunk() {
		givenUnknownKeyword("hhkthk");
		given(placeResolver.resolve(eq("hhkthk"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("hhkthk")).willReturn(KeywordInterpretation.noPosts());

		TriageDecision decision = triageService.triage("hhkthk", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.JUNK);
		assertThat(decision.resolvedStatus()).isEqualTo(SearchMissStatus.IGNORED);
	}

	@Test
	@DisplayName("블로그 글이 없어도 한글 검색어는 쓰레기로 단정하지 않는다")
	void triage_noPostsHangulStaysUnresolved() {
		givenUnknownKeyword("도쿄 팡멪");
		given(placeResolver.resolve(eq("도쿄 팡멪"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("도쿄 팡멪")).willReturn(KeywordInterpretation.noPosts());

		assertThat(triageService.triage("도쿄 팡멪", MISS_ID).verdict()).isEqualTo(SearchMissVerdict.UNRESOLVED);
	}

	@Test
	@DisplayName("삭제된 가게와만 일치하면 다시 등록할지 사람이 본다")
	void triage_existsButDeleted() {
		Store deleted = store(3L, "RONDO", StoreStatus.DELETED);
		givenUnknownKeyword("RONDO");
		given(placeResolver.resolve(eq("RONDO"), any())).willReturn(new PlaceResolution(
			List.of(place("100", "RONDO", "카페")), List.of(), List.of(deleted), List.of(), List.of()));

		TriageDecision decision = triageService.triage("RONDO", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.EXISTS_BUT_DELETED);
		assertThat(decision.matchedStoreId()).isEqualTo(3L);
	}

	@Test
	@DisplayName("우리 DB 에 없는 빵집이면 신규 가게 후보로 남긴다 — 등록은 사람이 한다")
	void triage_newStoreCandidate() {
		givenUnknownKeyword("새빵집");
		given(placeResolver.resolve(eq("새빵집"), any())).willReturn(new PlaceResolution(
			List.of(place("200", "새빵집", "베이커리")), List.of(), List.of(), List.of(),
			List.of(place("200", "새빵집", "베이커리"))));

		TriageDecision decision = triageService.triage("새빵집", MISS_ID);

		assertThat(decision.verdict()).isEqualTo(SearchMissVerdict.NEW_STORE_CANDIDATE);
		assertThat(decision.evidence()).containsKey("newStoreCandidates");
		verifyNoInteractions(interpreter);
	}

	@Test
	@DisplayName("정식 이름으로도 우리 검색에 없으면 그 이름으로 네이버를 한 번 더 찾아 신규 후보를 찾는다")
	void triage_blogThenNaverAgain() {
		givenUnknownKeyword("떰즈비");
		given(placeResolver.resolve(eq("떰즈비"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("떰즈비")).willReturn(KeywordInterpretation.store("카페떰비", List.of("l1")));
		given(storeSearchService.probe("카페떰비", properties.getNameProbeSize())).willReturn(EMPTY);
		given(placeResolver.resolve(eq("카페떰비"), any())).willReturn(new PlaceResolution(
			List.of(place("300", "카페떰비", "카페")), List.of(), List.of(), List.of(),
			List.of(place("300", "카페떰비", "카페"))));

		assertThat(triageService.triage("떰즈비", MISS_ID).verdict())
			.isEqualTo(SearchMissVerdict.NEW_STORE_CANDIDATE);
	}

	@Test
	@DisplayName("블로그 해석이 메뉴·지역이면 가게 아님")
	void triage_blogNotAStore() {
		givenUnknownKeyword("두쫀쿠");
		given(placeResolver.resolve(eq("두쫀쿠"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("두쫀쿠")).willReturn(KeywordInterpretation.notAStore("블로그 해석: MENU"));

		assertThat(triageService.triage("두쫀쿠", MISS_ID).verdict()).isEqualTo(SearchMissVerdict.NOT_A_STORE);
	}

	@Test
	@DisplayName("판정한 행은 검색어·발생 횟수·판정을 결과에 담고, 실패한 행은 사유를 담는다")
	void triageNextBatch_collectsItemsAndFailures() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 18, 6, 0);
		SearchMiss junk = SearchMiss.firstOccurrence("ㅋㅋ", "ㅋㅋㅋ", SearchOutcome.EMPTY, now);
		SearchMiss broken = SearchMiss.firstOccurrence("콜버", "콜버", SearchOutcome.EMPTY, now);
		ReflectionTestUtils.setField(junk, "id", 1L);
		given(searchMissRepository.findTriageCandidatesIncluding(eq(EnumSet.of(SearchMissVerdict.CORRECTED)),
			any(Pageable.class))).willReturn(List.of(junk, broken));
		given(storeSearchService.probe("콜버", 1)).willThrow(new IllegalStateException("DB 끊김"));

		SearchMissTriageBatchResult result = triageService.triageNextBatch();

		assertThat(result.items()).singleElement()
			.satisfies(item -> assertThat(item.decision().verdict()).isEqualTo(SearchMissVerdict.JUNK));
		assertThat(result.failures()).singleElement()
			.satisfies(failure -> assertThat(failure.reason()).contains("DB 끊김"));
		// JUNK 는 자동 적용 대상이 아니라 판정만 남긴다
		verify(commandService).recordDecision(eq(1L), any(), eq(false), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("자동 적용 대상 판정만 처리 상태를 바꾸게 기록한다")
	void triageNextBatch_appliesOnlyEnabledVerdicts() {
		properties.setAutoApplyVerdicts(EnumSet.of(SearchMissVerdict.JUNK));
		SearchMiss junk = SearchMiss.firstOccurrence("ㅋㅋ", "ㅋㅋㅋ", SearchOutcome.EMPTY, LocalDateTime.now());
		ReflectionTestUtils.setField(junk, "id", 1L);
		given(searchMissRepository.findTriageCandidatesIncluding(any(), any(Pageable.class))).willReturn(List.of(junk));

		triageService.triageNextBatch();

		verify(commandService).recordDecision(eq(1L), any(), eq(true), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("shadow 면 미판정 행만 꺼낸다 — 판정만 된 행을 다시 집지 않는다")
	void triageNextBatch_shadowPicksUntriagedOnly() {
		properties.setAutoApplyVerdicts(EnumSet.noneOf(SearchMissVerdict.class));
		given(searchMissRepository.findTriageCandidates(any(Pageable.class))).willReturn(List.of());

		triageService.triageNextBatch();

		verify(searchMissRepository, never()).findTriageCandidatesIncluding(any(), any());
	}

	@Test
	@DisplayName("네이버 한도 초과면 이번 회차를 멈추고 사유와 못 본 건수를 결과에 남긴다")
	void triageNextBatch_stopsOnNaverRateLimit() {
		SearchMiss limited = SearchMiss.firstOccurrence("콜버", "콜버", SearchOutcome.EMPTY, LocalDateTime.now());
		SearchMiss next = SearchMiss.firstOccurrence("새빵집", "새빵집", SearchOutcome.EMPTY, LocalDateTime.now());
		given(searchMissRepository.findTriageCandidatesIncluding(any(), any(Pageable.class)))
			.willReturn(List.of(limited, next));
		givenUnknownKeyword("콜버");
		given(placeResolver.resolve(eq("콜버"), any()))
			.willThrow(new NaverSearchException(NaverSearchErrorCode.RATE_LIMITED));

		SearchMissTriageBatchResult result = triageService.triageNextBatch();

		assertThat(result.stoppedReason()).contains("네이버");
		assertThat(result.skipped()).isEqualTo(2);
		verify(commandService, never()).recordDecision(any(), any(), anyBoolean(), any());
	}

	@Test
	@DisplayName("LLM 한도 초과도 이번 회차를 멈춘다")
	void triageNextBatch_stopsOnLlmRateLimit() {
		SearchMiss miss = SearchMiss.firstOccurrence("콜버", "콜버", SearchOutcome.EMPTY, LocalDateTime.now());
		given(searchMissRepository.findTriageCandidatesIncluding(any(), any(Pageable.class))).willReturn(List.of(miss));
		givenUnknownKeyword("콜버");
		given(placeResolver.resolve(eq("콜버"), any())).willReturn(PlaceResolution.none());
		given(interpreter.interpret("콜버")).willThrow(new LlmException(LlmErrorCode.RATE_LIMITED));

		assertThat(triageService.triageNextBatch().stoppedReason()).contains("LLM");
	}
}
