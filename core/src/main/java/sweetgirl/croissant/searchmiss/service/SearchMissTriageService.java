package sweetgirl.croissant.searchmiss.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.searchmiss.entity.SearchMiss;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;
import sweetgirl.croissant.searchmiss.repository.SearchMissRepository;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchOutcome;
import sweetgirl.croissant.store.service.search.StoreSearchProbe;
import sweetgirl.croissant.store.service.search.StoreSearchQueryParser;
import sweetgirl.croissant.store.service.search.StoreSearchService;

/**
 * 실패 검색어 자동 판정 파이프라인. 싼 판정부터 돌리고 결론이 나면 멈춘다.
 *
 * <ol>
 *   <li>쓰레기 입력 → JUNK</li>
 *   <li>지금 다시 검색(기록 없는 probe) → HIT 면 NOW_HIT. 지역·빵 사전 단어로만 된 검색어 → NOT_A_STORE</li>
 *   <li>네이버 지역검색 → place ID / 좌표+이름으로 우리 가게와 대조</li>
 *   <li>장소가 안 나오면 블로그 + LLM 으로 정식 이름을 풀고, 그 이름으로 2·3 을 다시</li>
 *   <li>우리 가게로 확인되면 검색어 교정을 넣고 다시 검색해 나오는지 확인</li>
 * </ol>
 *
 * <p>있다/없다는 코드가 정한다. LLM 은 4 단계에서 "이 말이 무엇을 가리키나"만 해석하고, 그 해석도 코드가 근거와 대조한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchMissTriageService {

	private static final int HIT_PROBE_SIZE = 1;

	private final SearchMissRepository searchMissRepository;
	private final StoreRepository storeRepository;
	private final StoreSearchService storeSearchService;
	private final StoreSearchQueryParser storeSearchQueryParser;
	private final SearchMissPlaceResolver searchMissPlaceResolver;
	private final SearchMissKeywordInterpreter searchMissKeywordInterpreter;
	private final SearchMissTriageCommandService searchMissTriageCommandService;
	private final SearchMissTriageProperties properties;

	/**
	 * 대상 행을 발생 횟수 순으로 꺼내 판정한다.
	 *
	 * <p>네이버·LLM 한도 초과면 이번 회차를 멈춘다 — 남은 행에 계속 부딪혀 봐야 실패만 쌓인다. 멈춘 사유는 결과에 담아
	 * 리포트로 알린다. 그 밖의 실패는 그 행만 건너뛴다. 건너뛴 행은 {@code ai_triaged_at}이 안 찍혀 다음 날 다시 집힌다.
	 */
	public SearchMissTriageBatchResult triageNextBatch() {
		Set<SearchMissVerdict> reapplyVerdicts = properties.getAutoApplyVerdicts();
		PageRequest page = PageRequest.of(0, properties.getBatchSize());
		List<SearchMiss> candidates = reapplyVerdicts.isEmpty()
			? searchMissRepository.findTriageCandidates(page)
			: searchMissRepository.findTriageCandidatesIncluding(reapplyVerdicts, page);
		List<SearchMissTriageItem> items = new ArrayList<>();
		List<SearchMissTriageFailure> failures = new ArrayList<>();
		String stoppedReason = null;
		for (SearchMiss candidate : candidates) {
			try {
				TriageDecision decision = triage(candidate.getKeyword(), candidate.getId());
				searchMissTriageCommandService.recordDecision(candidate.getId(), decision,
					properties.autoApplies(decision.verdict()), LocalDateTime.now());
				items.add(new SearchMissTriageItem(candidate.getKeyword(), candidate.getOccurrenceCount(), decision));
			} catch (NaverSearchException exception) {
				if (exception.isRateLimited()) {
					stoppedReason = "네이버 검색 API 한도 초과";
					log.warn("[검색어 판정] {} — 이번 회차를 멈춘다 keyword={}", stoppedReason, candidate.getKeyword());
					break;
				}
				failures.add(fail(candidate, "네이버 검색 실패", exception));
			} catch (LlmException exception) {
				if (exception.isRetryable()) {
					stoppedReason = "LLM 한도 초과·일시 장애";
					log.warn("[검색어 판정] {} — 이번 회차를 멈춘다 keyword={}", stoppedReason, candidate.getKeyword());
					break;
				}
				failures.add(fail(candidate, "LLM 실패", exception));
			} catch (RuntimeException exception) {
				failures.add(fail(candidate, "판정 실패", exception));
			}
		}
		return new SearchMissTriageBatchResult(candidates.size(), items, failures, stoppedReason);
	}

	private SearchMissTriageFailure fail(SearchMiss candidate, String label, RuntimeException exception) {
		log.warn("[검색어 판정] {} keyword={}", label, candidate.getKeyword(), exception);
		return new SearchMissTriageFailure(candidate.getKeyword(), label + ": " + exception.getMessage());
	}

	public TriageDecision triage(String keyword) {
		return triage(keyword, null);
	}

	/**
	 * 검색어 하나를 판정한다. 교정(CORRECTED)이 자동 적용 대상이면 교정까지 넣는다(처리 상태 변경은 {@link #triageNextBatch}가
	 * 기록하며 한다).
	 *
	 * @param searchMissId 교정의 근거로 남길 실패 검색어 id
	 */
	public TriageDecision triage(String keyword, Long searchMissId) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		List<String> steps = new ArrayList<>();
		evidence.put("steps", steps);

		steps.add("JUNK_FILTER");
		if (SearchMissTextMatcher.isJunk(keyword)) {
			String reason = SearchMissTextMatcher.containsPersonalInfo(keyword) ? "개인정보(이메일·전화번호)" : "쓰레기 입력";
			return decide(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null, evidence, reason);
		}

		steps.add("RE_SEARCH");
		StoreSearchProbe probe = storeSearchService.probe(keyword, HIT_PROBE_SIZE);
		if (probe.isHit()) {
			return decide(SearchMissVerdict.NOW_HIT, SearchMissStatus.ALREADY_EXISTS, firstOrNull(probe.storeIds()),
				evidence, "지금은 검색됨");
		}
		SearchIntent intent = storeSearchQueryParser.parse(keyword);
		if (probe.outcome() == SearchOutcome.MISMATCH || StoreSearchService.isFullyClassified(intent)) {
			return decide(SearchMissVerdict.NOT_A_STORE, null, null, evidence, "지역·빵 사전 단어로만 된 검색어");
		}
		// 지역 단어가 섞인 검색어("성심당 롯백", "어니언 성수")를 교정하면 지역 조건이 사라진다. 빵 단어는 괜찮다 —
		// 빵 단어로만 된 검색어는 위에서 이미 걸렀고, "파리바게트"처럼 이름에 빵 단어가 든 가게는 교정해야 한다
		boolean correctionAllowed = intent.regionTokens().isEmpty()
			&& storeSearchQueryParser.parseWithGluedTokenFallback(keyword).regionTokens().isEmpty();
		evidence.put("correctionAllowed", correctionAllowed);
		TriageContext context = new TriageContext(keyword, searchMissId, correctionAllowed, evidence);

		steps.add("NAVER_LOCAL");
		PlaceResolution resolution = searchMissPlaceResolver.resolve(keyword,
			placeName -> SearchMissTextMatcher.isRelated(keyword, placeName));
		Optional<TriageDecision> fromPlaces = decideFromPlaces(context, null, resolution);
		if (fromPlaces.isPresent()) {
			return fromPlaces.get();
		}

		steps.add("BLOG_LLM");
		KeywordInterpretation interpretation = searchMissKeywordInterpreter.interpret(keyword);
		evidence.put("interpretation", interpretationSummary(interpretation));
		switch (interpretation.kind()) {
			case NOT_A_STORE -> {
				return decide(SearchMissVerdict.NOT_A_STORE, null, null, evidence, interpretation.reason());
			}
			case NO_POSTS -> {
				// 네이버 장소도 블로그 글도 없는, 한글 없는 문자열(hhkthk)은 아무도 쓰지 않는 말이다
				if (resolution.hasNoRelevantPlace() && !SearchMissTextMatcher.hasHangul(keyword)) {
					return decide(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null, evidence,
						"네이버 장소·블로그 글 모두 없는 문자열");
				}
				return decide(SearchMissVerdict.UNRESOLVED, null, null, evidence, interpretation.reason());
			}
			case UNRESOLVED -> {
				return decide(SearchMissVerdict.UNRESOLVED, null, null, evidence, interpretation.reason());
			}
			default -> {
				// STORE — 아래에서 우리 가게와 대조한다
			}
		}
		String officialName = interpretation.officialName();

		steps.add("OFFICIAL_NAME_SEARCH");
		StoreSearchProbe nameProbe = storeSearchService.probe(officialName, properties.getNameProbeSize());
		if (nameProbe.isHit()) {
			List<Store> sameName = storeRepository.findAllById(nameProbe.storeIds()).stream()
				.filter(store -> SearchMissTextMatcher.isSameStoreName(store.getName(), officialName))
				.toList();
			Optional<TriageDecision> fromStores = decideFromStores(context, officialName, sameName);
			if (fromStores.isPresent()) {
				return fromStores.get();
			}
		}

		steps.add("OFFICIAL_NAME_NAVER_LOCAL");
		PlaceResolution officialResolution = searchMissPlaceResolver.resolve(officialName,
			placeName -> SearchMissTextMatcher.isSameStoreName(placeName, officialName));
		return decideFromPlaces(context, officialName, officialResolution)
			.orElseGet(() -> decide(SearchMissVerdict.UNRESOLVED, null, null, evidence,
				"'" + officialName + "'을(를) 우리 가게·네이버 장소에서 못 찾음"));
	}

	/**
	 * 네이버 장소를 대조한 결과로 판정한다. 관련 장소가 빵집·카페가 아닌 것뿐이면 결론을 내지 않는다 — 블로그 단계로 넘긴다.
	 * "뚜쥬르"에 "뚜쥬르 빵돌가마마을 전기차충전소"만 나와 블로그도 안 보고 끝난 일이 있었다(운영 1회차 10/17).
	 *
	 * @param targetHint 블로그로 풀어낸 정식 이름. 원래 검색어로 찾을 때는 null
	 */
	private Optional<TriageDecision> decideFromPlaces(TriageContext context, String targetHint,
			PlaceResolution resolution) {
		if (resolution.hasNoRelevantPlace()) {
			return Optional.empty();
		}
		Map<String, Object> evidence = context.evidence();
		evidence.put("naverPlaces", resolution.relevantPlaces().stream().map(this::placeSummary).toList());
		Optional<TriageDecision> fromStores = decideFromStores(context, targetHint,
			concat(resolution.activeStores(), resolution.deletedStores()));
		if (fromStores.isPresent()) {
			return fromStores;
		}
		if (!resolution.conflictingPlaces().isEmpty()) {
			return Optional.of(decide(SearchMissVerdict.UNRESOLVED, null, null, evidence,
				"네이버 장소 바로 옆에 이름이 다른 우리 가게가 있음(가게 변경 가능성)"));
		}
		if (!resolution.newStoreCandidates().isEmpty()) {
			evidence.put("newStoreCandidates",
				resolution.newStoreCandidates().stream().map(this::placeSummary).toList());
			return Optional.of(decide(SearchMissVerdict.NEW_STORE_CANDIDATE, null, null, evidence,
				"네이버에는 있는 빵집·카페인데 우리 DB 에 없음"));
		}
		evidence.put("nonBakeryPlacesOnly", true);
		return Optional.empty();
	}

	/** 같은 가게로 확인된 우리 가게들로 판정한다. 삭제된 가게만 있으면 사람이 본다. */
	private Optional<TriageDecision> decideFromStores(TriageContext context, String targetHint, List<Store> stores) {
		List<Store> activeStores = stores.stream().filter(store -> store.getStatus() == StoreStatus.ACTIVE).toList();
		if (!activeStores.isEmpty()) {
			return Optional.of(resolveCorrection(context, targetHint, activeStores));
		}
		if (!stores.isEmpty()) {
			context.evidence().put("matchedStoreName", stores.get(0).getName());
			return Optional.of(decide(SearchMissVerdict.EXISTS_BUT_DELETED, null, stores.get(0).getId(),
				context.evidence(), "삭제된 가게와 일치"));
		}
		return Optional.empty();
	}

	/**
	 * 우리 가게(브랜드)의 오타·줄임말·별명으로 확인됐다. 검색어 교정을 넣고, 다시 검색해 나오는지 확인한다.
	 *
	 * <p>나오지 않으면 넣은 교정을 되돌린다 — "처리했다는데 실제로는 안 고쳐진" 상태를 남기지 않는다.
	 */
	private TriageDecision resolveCorrection(TriageContext context, String targetHint, List<Store> matchedStores) {
		Map<String, Object> evidence = context.evidence();
		Long representativeId = matchedStores.get(0).getId();
		evidence.put("matchedStoreIds", matchedStores.stream().map(Store::getId).toList());
		evidence.put("matchedStoreName", SearchMissTextMatcher.displayBaseName(matchedStores.get(0).getName()));
		if (!context.correctionAllowed()) {
			return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
				"지역 단어가 섞인 검색어라 교정하지 않음");
		}
		Optional<String> target = correctionTarget(context.keyword(), targetHint, matchedStores);
		if (target.isEmpty()) {
			return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
				"검색어가 서로 다른 가게 여럿과 일치: " + baseNames(matchedStores));
		}
		evidence.put("correctionTarget", target.get());
		if (!properties.autoApplies(SearchMissVerdict.CORRECTED)) {
			return decide(SearchMissVerdict.CORRECTED, SearchMissStatus.ALREADY_EXISTS, representativeId, evidence,
				"shadow — 교정은 넣지 않음");
		}

		Optional<Long> correctionId = searchMissTriageCommandService.addCorrection(context.keyword(), target.get(),
			context.searchMissId());
		if (correctionId.isEmpty()) {
			return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
				"같은 검색어 교정이 이미 있는데도 검색되지 않음");
		}
		if (storeSearchService.probe(context.keyword(), HIT_PROBE_SIZE).isHit()) {
			evidence.put("correctionId", correctionId.get());
			return decide(SearchMissVerdict.CORRECTED, SearchMissStatus.ALREADY_EXISTS, representativeId, evidence,
				"교정 추가 후 검색됨");
		}
		searchMissTriageCommandService.removeCorrection(correctionId.get());
		return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
			"교정해도 검색되지 않아 되돌림");
	}

	/**
	 * 무엇으로 교정할지 정한다.
	 *
	 * <ul>
	 *   <li>가게 이름(지점명 뗀)이 하나면 그 이름 — "콜드버터베이크샵 탄방점" → "콜드버터베이크샵"</li>
	 *   <li>여럿이면 블로그로 풀어낸 이름, 없으면 이름들의 공통 앞부분을 브랜드로 본다. 단 <b>검색어가 그 브랜드의 오타·줄임말·포함</b>
	 *       일 때만 — "서심당" → "성심당"은 되지만, 특정 매장을 브랜드로 뭉뚱그린 "샌드위치 정류장" → "성심당"은 막는다</li>
	 * </ul>
	 */
	private Optional<String> correctionTarget(String keyword, String targetHint, List<Store> matchedStores) {
		List<String> baseNames = baseNames(matchedStores);
		if (baseNames.size() == 1) {
			return Optional.of(SearchMissTextMatcher.displayBaseName(matchedStores.get(0).getName()));
		}
		String brand = targetHint != null ? targetHint : SearchMissTextMatcher.commonPrefix(baseNames);
		if (!brand.isBlank() && SearchMissTextMatcher.isRelated(keyword, brand)) {
			return Optional.of(brand);
		}
		return Optional.empty();
	}

	private static List<String> baseNames(List<Store> stores) {
		Set<String> names = new LinkedHashSet<>();
		stores.forEach(store -> names.add(SearchMissTextMatcher.baseName(store.getName())));
		return List.copyOf(names);
	}

	private static TriageDecision decide(SearchMissVerdict verdict, SearchMissStatus status, Long matchedStoreId,
			Map<String, Object> evidence, String reason) {
		evidence.put("reason", reason);
		return new TriageDecision(verdict, status, matchedStoreId, evidence);
	}

	private Map<String, Object> placeSummary(NaverPlaceSearchResult place) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("name", place.name());
		summary.put("naverPlaceId", place.naverPlaceId());
		summary.put("category", place.category());
		summary.put("address", place.address());
		return summary;
	}

	private static Map<String, Object> interpretationSummary(KeywordInterpretation interpretation) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("kind", interpretation.kind().name());
		summary.put("officialName", interpretation.officialName());
		summary.put("evidenceLinks", interpretation.evidenceLinks());
		summary.put("reason", interpretation.reason());
		return summary;
	}

	private static List<Store> concat(List<Store> first, List<Store> second) {
		List<Store> merged = new ArrayList<>(first);
		merged.addAll(second);
		return merged;
	}

	private static Long firstOrNull(List<Long> ids) {
		return ids.isEmpty() ? null : ids.get(0);
	}

	/** 행 하나를 판정하는 동안 단계들이 함께 쓰는 값 */
	private record TriageContext(
			String keyword,
			Long searchMissId,
			boolean correctionAllowed,
			Map<String, Object> evidence) {
	}
}
