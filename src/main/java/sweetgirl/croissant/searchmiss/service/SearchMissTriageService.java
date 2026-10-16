package sweetgirl.croissant.searchmiss.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
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
 *   <li>우리 가게로 확인되면 별칭을 넣고 다시 검색해 나오는지 확인</li>
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
	 * <p>네이버·LLM 한도 초과는 다시 던져 이번 회차를 멈춘다 — 남은 행에 계속 부딪혀 봐야 실패만 쌓인다.
	 * 그 밖의 실패는 그 행만 건너뛴다. 건너뛴 행은 {@code ai_triaged_at}이 안 찍혀 다음 날 배치에서 다시 집힌다.
	 */
	public SearchMissTriageBatchResult triageNextBatch() {
		List<SearchMiss> candidates = searchMissRepository.findTriageCandidates(
			PageRequest.of(0, properties.getBatchSize()));
		Map<SearchMissVerdict, Integer> verdictCounts = new EnumMap<>(SearchMissVerdict.class);
		int failed = 0;
		for (SearchMiss candidate : candidates) {
			try {
				TriageDecision decision = triage(candidate.getKeyword());
				searchMissTriageCommandService.recordDecision(candidate.getId(), decision, properties.isAutoApply(),
					LocalDateTime.now());
				verdictCounts.merge(decision.verdict(), 1, Integer::sum);
			} catch (NaverSearchException exception) {
				if (exception.isRateLimited()) {
					throw exception;
				}
				failed++;
				log.warn("[검색어 판정] 네이버 검색 실패 keyword={}", candidate.getKeyword(), exception);
			} catch (LlmException exception) {
				if (exception.isRetryable()) {
					throw exception;
				}
				failed++;
				log.warn("[검색어 판정] LLM 실패 keyword={}", candidate.getKeyword(), exception);
			} catch (RuntimeException exception) {
				failed++;
				log.warn("[검색어 판정] 실패 keyword={}", candidate.getKeyword(), exception);
			}
		}
		return new SearchMissTriageBatchResult(candidates.size(), verdictCounts, failed);
	}

	/** 검색어 하나를 판정한다. auto-apply 면 별칭까지 넣는다(처리 상태 변경은 {@link #triageNextBatch}가 기록하며 한다). */
	public TriageDecision triage(String keyword) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		List<String> steps = new ArrayList<>();
		evidence.put("steps", steps);

		steps.add("JUNK_FILTER");
		if (SearchMissTextMatcher.isJunk(keyword)) {
			return decide(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null, evidence, "쓰레기 입력");
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
		boolean aliasAllowed = !hasDictionaryTokens(intent)
			&& !hasDictionaryTokens(storeSearchQueryParser.parseWithGluedTokenFallback(keyword));
		evidence.put("aliasAllowed", aliasAllowed);

		steps.add("NAVER_LOCAL");
		PlaceResolution resolution = searchMissPlaceResolver.resolve(keyword,
			placeName -> SearchMissTextMatcher.isRelated(keyword, placeName));
		Optional<TriageDecision> fromPlaces = decideFromPlaces(keyword, resolution, aliasAllowed, evidence);
		if (fromPlaces.isPresent()) {
			return fromPlaces.get();
		}

		steps.add("BLOG_LLM");
		KeywordInterpretation interpretation = searchMissKeywordInterpreter.interpret(keyword);
		evidence.put("interpretation", interpretationSummary(interpretation));
		if (interpretation.kind() == KeywordInterpretation.Kind.NOT_A_STORE) {
			return decide(SearchMissVerdict.NOT_A_STORE, null, null, evidence, interpretation.reason());
		}
		if (interpretation.kind() == KeywordInterpretation.Kind.UNRESOLVED) {
			return decide(SearchMissVerdict.UNRESOLVED, null, null, evidence, interpretation.reason());
		}
		String officialName = interpretation.officialName();

		steps.add("OFFICIAL_NAME_SEARCH");
		StoreSearchProbe nameProbe = storeSearchService.probe(officialName, properties.getMaxAliasStores());
		if (nameProbe.isHit()) {
			List<Store> sameName = storeRepository.findAllById(nameProbe.storeIds()).stream()
				.filter(store -> SearchMissTextMatcher.isSameStoreName(store.getName(), officialName))
				.toList();
			Optional<TriageDecision> fromStores = decideFromStores(keyword, sameName, aliasAllowed, evidence);
			if (fromStores.isPresent()) {
				return fromStores.get();
			}
		}

		steps.add("OFFICIAL_NAME_NAVER_LOCAL");
		PlaceResolution officialResolution = searchMissPlaceResolver.resolve(officialName,
			placeName -> SearchMissTextMatcher.isSameStoreName(placeName, officialName));
		return decideFromPlaces(keyword, officialResolution, aliasAllowed, evidence)
			.orElseGet(() -> decide(SearchMissVerdict.UNRESOLVED, null, null, evidence,
				"'" + officialName + "'을(를) 우리 가게·네이버 장소에서 못 찾음"));
	}

	private Optional<TriageDecision> decideFromPlaces(String keyword, PlaceResolution resolution,
			boolean aliasAllowed, Map<String, Object> evidence) {
		if (resolution.hasNoRelevantPlace()) {
			return Optional.empty();
		}
		evidence.put("naverPlaces", resolution.relevantPlaces().stream().map(this::placeSummary).toList());
		Optional<TriageDecision> fromStores = decideFromStores(keyword,
			concat(resolution.activeStores(), resolution.deletedStores()), aliasAllowed, evidence);
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
		return Optional.of(decide(SearchMissVerdict.UNRESOLVED, null, null, evidence,
			"관련 장소가 빵집·카페가 아님"));
	}

	/** 같은 가게로 확인된 우리 가게들로 판정한다. 삭제된 가게만 있으면 사람이 본다. */
	private Optional<TriageDecision> decideFromStores(String keyword, List<Store> stores, boolean aliasAllowed,
			Map<String, Object> evidence) {
		List<Store> activeStores = stores.stream().filter(store -> store.getStatus() == StoreStatus.ACTIVE).toList();
		if (!activeStores.isEmpty()) {
			return Optional.of(resolveAlias(keyword, activeStores, aliasAllowed, evidence));
		}
		if (!stores.isEmpty()) {
			return Optional.of(decide(SearchMissVerdict.EXISTS_BUT_DELETED, null, stores.get(0).getId(), evidence,
				"삭제된 가게와 일치"));
		}
		return Optional.empty();
	}

	/**
	 * 우리 가게의 줄임말·오타로 확인됐다. 체인 지점까지 모아 별칭을 넣고, 다시 검색해 나오는지 확인한다.
	 *
	 * <p>나오지 않으면 넣은 별칭을 되돌린다 — "처리했다는데 실제로는 안 고쳐진" 상태를 남기지 않는다.
	 */
	private TriageDecision resolveAlias(String keyword, List<Store> matchedStores, boolean aliasAllowed,
			Map<String, Object> evidence) {
		Long representativeId = matchedStores.get(0).getId();
		evidence.put("matchedStoreIds", matchedStores.stream().map(Store::getId).toList());
		if (!aliasAllowed) {
			return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
				"지역·빵 단어가 섞인 검색어라 별칭으로 넣지 않음");
		}
		Set<String> baseNames = new LinkedHashSet<>();
		matchedStores.forEach(store -> baseNames.add(SearchMissTextMatcher.baseName(store.getName())));
		if (baseNames.size() > 1) {
			return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
				"검색어가 서로 다른 가게 여럿과 일치: " + baseNames);
		}
		Optional<List<Long>> targetIds = collectBranches(baseNames.iterator().next(), matchedStores);
		if (targetIds.isEmpty()) {
			return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
				"지점이 " + properties.getMaxAliasStores() + "곳을 넘음");
		}
		evidence.put("aliasStoreIds", targetIds.get());
		if (!properties.isAutoApply()) {
			return decide(SearchMissVerdict.ALIAS_RESOLVED, SearchMissStatus.ALREADY_EXISTS, representativeId,
				evidence, "shadow mode — 별칭은 넣지 않음");
		}

		List<Long> insertedAliasIds = searchMissTriageCommandService.addAliases(keyword.trim(), targetIds.get());
		if (storeSearchService.probe(keyword, HIT_PROBE_SIZE).isHit()) {
			evidence.put("insertedAliasIds", insertedAliasIds);
			return decide(SearchMissVerdict.ALIAS_RESOLVED, SearchMissStatus.ALREADY_EXISTS, representativeId,
				evidence, "별칭 추가 후 검색됨");
		}
		searchMissTriageCommandService.removeAliases(insertedAliasIds);
		return decide(SearchMissVerdict.UNRESOLVED, null, representativeId, evidence,
			"별칭을 넣어도 검색되지 않아 되돌림");
	}

	/**
	 * 같은 이름(지점명 뗀)의 ACTIVE 지점을 모은다. 한 지점에만 별칭을 달면 다른 지점은 그 말로 안 나온다.
	 *
	 * @return 상한을 넘으면 비어 있다
	 */
	private Optional<List<Long>> collectBranches(String baseName, List<Store> matchedStores) {
		Set<Long> ids = new LinkedHashSet<>();
		matchedStores.forEach(store -> ids.add(store.getId()));
		StoreSearchProbe branchProbe = storeSearchService.probe(baseName, properties.getMaxAliasStores());
		if (branchProbe.hasMore()) {
			return Optional.empty();
		}
		storeRepository.findAllById(branchProbe.storeIds()).stream()
			.filter(store -> store.getStatus() == StoreStatus.ACTIVE)
			.filter(store -> SearchMissTextMatcher.baseName(store.getName()).equals(baseName))
			.forEach(store -> ids.add(store.getId()));
		if (ids.size() > properties.getMaxAliasStores()) {
			return Optional.empty();
		}
		return Optional.of(List.copyOf(ids));
	}

	private static boolean hasDictionaryTokens(SearchIntent intent) {
		return !intent.regionTokens().isEmpty() || !intent.breadTokens().isEmpty();
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
}
