package sweetgirl.croissant.store.service.search;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.searchmiss.event.SearchMissEvent;
import sweetgirl.croissant.store.code.StoreSearchErrorCode;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.exception.StoreSearchException;
import sweetgirl.croissant.store.service.trending.TrendingRankingReader;
import sweetgirl.croissant.store.service.trending.TrendingStore;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreSearchService {

	// 지표·적재는 첫 페이지에서만 센다. 2페이지 이후는 새 검색이 아니라 같은 검색의 스크롤이다.
	private static final int FIRST_PAGE = 0;
	private static final int DEFAULT_AUTOCOMPLETE_SIZE = 10;
	private static final int MAX_AUTOCOMPLETE_SIZE = 20;
	private static final String SEARCH_QUERY_METRIC = "search.query";
	private static final String SEARCH_FALLBACK_METRIC = "search.fallback";
	private static final String TYPE_SEARCH = "search";
	private static final String TYPE_AUTOCOMPLETE = "autocomplete";
	private static final int SEARCH_TRACE_TOP_HITS = 10;
	// 검색 부하는 이제 운영 RDB 에 직접 실린다. 비로그인 엔드포인트라 size 는 상한으로 자르고(자동완성 20 과 같은 취지),
	// 좌표는 범위 밖이면 PostGIS 가 500 을 내므로 400 으로 먼저 거른다.
	static final int MAX_SEARCH_PAGE_SIZE = 50;

	private final StoreSearchQueryParser storeSearchQueryParser;
	private final StoreSearchEngine storeSearchEngine;
	private final StoreSearchAssembler storeSearchAssembler;
	private final MeterRegistry meterRegistry;
	private final ApplicationEventPublisher eventPublisher;
	private final TrendingRankingReader trendingRankingReader;
	private final StoreTrendingProperties storeTrendingProperties;
	private final SearchKeywordCorrector searchKeywordCorrector;

	private final Map<SearchOutcome, Counter> searchCounters = new EnumMap<>(SearchOutcome.class);
	private final Map<SearchOutcome, Counter> autocompleteCounters = new EnumMap<>(SearchOutcome.class);
	private final Map<SearchFallbackStage, Counter> fallbackCounters = new EnumMap<>(SearchFallbackStage.class);

	// Counter는 최초 증가 전까지 /actuator/prometheus에 노출되지 않는다. 대시보드에서 "0건"과
	// "계측이 안 됨"을 구분할 수 있도록 부팅 시 태그 조합을 미리 등록해 0부터 그려지게 한다.
	@PostConstruct
	void registerSearchOutcomeCounters() {
		for (SearchOutcome outcome : SearchOutcome.values()) {
			searchCounter(outcome);
		}
		// 자동완성은 후보 문자열 자체를 엔진에서 이미 검증하므로 hit/empty, 그리고 SQL 이 끊긴 timeout 만 발생한다.
		autocompleteCounter(SearchOutcome.HIT);
		autocompleteCounter(SearchOutcome.EMPTY);
		autocompleteCounter(SearchOutcome.TIMEOUT);
		for (SearchFallbackStage stage : SearchFallbackStage.values()) {
			if (stage != SearchFallbackStage.NONE) {
				fallbackCounter(stage);
			}
		}
	}

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Double latitude, Double longitude,
			SearchSort sort, Pageable requestedPageable) {
		validateCoordinates(latitude, longitude);
		Pageable pageable = clampPageSize(requestedPageable);
		SearchExecution execution = execute(keyword, latitude, longitude, sort, pageable);
		StoreSearchResult result = execution.result();
		boolean fallbackApplied = execution.fallbackStage() != SearchFallbackStage.NONE;
		// 2페이지 이후는 같은 검색의 연속이므로 세지 않는다. 무한스크롤이 검색 API를 페이지마다
		// 호출하는데, 이걸 다 세면 "많이 스크롤한 검색어"가 "여러 사람이 찾은 검색어"보다 위로 올라와
		// 실패 목록의 우선순위가 뒤집힌다. 실제로 "천안시 서북구"가 검색 5회 + 스크롤 6페이지로
		// 11회가 되어, 2회인 다른 검색어보다 높게 잡혔다.
		if (pageable.getPageNumber() == FIRST_PAGE) {
			recordSearchOutcome(execution.outcome(), keyword);
			if (fallbackApplied) {
				fallbackCounter(execution.fallbackStage()).increment();
			}
		}
		logSearchTrace(keyword, execution.correctedKeyword(), execution.intent(), result, execution.outcome(),
			execution.fallbackStage(), execution.keptToken(), pageable);
		if (result.hits().isEmpty()) {
			return new SliceResponse<>(List.of(), false);
		}
		return new SliceResponse<>(storeSearchAssembler.assemble(result.hits()), result.hasNext());
	}

	/**
	 * 사용자 검색과 같은 경로로 돌려 판정만 받는다 — 실패 검색어 자동 판정이 "지금 검색하면 나오나"를 확인할 때 쓴다.
	 *
	 * <p>지표·SearchMissEvent·추적 로그를 남기지 않는다. {@link #searchStores}를 그대로 부르면 실패할 때마다
	 * search_misses 의 발생 횟수가 올라, 판정이 판정 대상을 부풀린다.
	 *
	 * @param size 돌려받을 가게 수. 체인 지점을 모으는 용도라 {@link #MAX_SEARCH_PAGE_SIZE}까지
	 */
	public StoreSearchProbe probe(String keyword, int size) {
		Pageable pageable = PageRequest.of(FIRST_PAGE, Math.min(Math.max(size, 1), MAX_SEARCH_PAGE_SIZE));
		SearchExecution execution = execute(keyword, null, null, SearchSort.RELEVANCE, pageable);
		List<Long> storeIds = execution.result().hits().stream()
			.map(StoreSearchHit::storeId)
			.toList();
		return new StoreSearchProbe(execution.outcome(), storeIds, execution.result().hasNext());
	}

	/**
	 * 검색어 교정("서심당" → "성심당")을 먼저 적용하고 돌린다. 실패 적재·지표는 부르는 쪽이 원문으로 남긴다 — 사람들이 실제로 친
	 * 말을 봐야 교정이 맞았는지 알 수 있다.
	 */
	private SearchExecution execute(String originalKeyword, Double latitude, Double longitude, SearchSort sort,
			Pageable pageable) {
		String keyword = searchKeywordCorrector.correct(originalKeyword);
		// 1차: 가게명 우선 — "브릭베이글"·"소금도화"처럼 붙여쓴 토큰은 통째로 가게명 검색
		SearchIntent intent = storeSearchQueryParser.parse(keyword);
		StoreSearchResult result = runSearch(intent, pageable, latitude, longitude, sort, keyword);
		// 폴백: 1차가 0건일 때만 붙어쓴 토큰을 분해해 다시 검색한다
		// ("초코소금빵"→빵 "소금빵", "한남에그타르트"→지역 "한남" + 빵 "에그타르트").
		// 지역·빵 어느 쪽도 새로 분해되지 않으면 같은 쿼리가 되므로 재검색하지 않는다.
		SearchFallbackStage fallbackStage = SearchFallbackStage.NONE;
		String keptToken = "";
		List<SearchIntent> relaxableIntents = new ArrayList<>(List.of(intent));
		if (result.hits().isEmpty()) {
			SearchIntent fallbackIntent = storeSearchQueryParser.parseWithGluedTokenFallback(keyword);
			if (isDecomposed(fallbackIntent, intent)) {
				result = runSearch(fallbackIntent, pageable, latitude, longitude, sort, keyword);
				relaxableIntents.add(fallbackIntent);
				if (!result.hits().isEmpty()) {
					fallbackStage = SearchFallbackStage.GLUED_TOKEN;
				}
			}
		}
		// 토큰 완화: 위 단계가 전부 0건일 때만 탄다. 여러 토큰이 전부 AND 라 "성수 어니언"(가게 "어니언 성수")이나
		// "사과당 망원"(망원에 지점 없음)이 0건으로 끝나던 것을, 어순 무관 이름 매칭 → 가장 구체적인 토큰 하나 순으로 푼다.
		if (result.hits().isEmpty()) {
			Optional<StoreSearchRelaxedResult> relaxed = runRelaxedSearch(relaxableIntents, pageable, latitude,
				longitude, sort, keyword);
			if (relaxed.isPresent()) {
				result = relaxed.get().result();
				fallbackStage = relaxed.get().stage();
				keptToken = relaxed.get().keptToken();
			}
		}
		SearchOutcome outcome = resolveSearchOutcome(result, intent, fallbackStage != SearchFallbackStage.NONE);
		return new SearchExecution(keyword, intent, result, outcome, fallbackStage, keptToken);
	}

	/** @param correctedKeyword 교정을 거친 실제 검색어. 교정이 없으면 원문과 같다 */
	private record SearchExecution(
			String correctedKeyword,
			SearchIntent intent,
			StoreSearchResult result,
			SearchOutcome outcome,
			SearchFallbackStage fallbackStage,
			String keptToken) {
	}

	/**
	 * 검색 SQL 이 statement 타임아웃(StoreSearchProperties.queryTimeoutSeconds)에 끊기면 503 으로 답한다. 운영 측정상 수백 ms 가
	 * 상한인 쿼리가 2초를 넘는 건 DB·커넥션이 멈춘 것이라, 요청 스레드를 붙잡아 nginx 504 까지 끌고 가는 대신 빨리 끊는다.
	 */
	private StoreSearchResult runSearch(SearchIntent intent, Pageable pageable, Double latitude, Double longitude,
			SearchSort sort, String keyword) {
		return withSearchTimeout(keyword,
			() -> storeSearchEngine.searchStores(intent, pageable, latitude, longitude, sort));
	}

	private Optional<StoreSearchRelaxedResult> runRelaxedSearch(List<SearchIntent> intents, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort, String keyword) {
		return withSearchTimeout(keyword,
			() -> storeSearchEngine.searchStoresRelaxed(intents, pageable, latitude, longitude, sort));
	}

	private <T> T withSearchTimeout(String keyword, Supplier<T> search) {
		try {
			return search.get();
		} catch (QueryTimeoutException exception) {
			searchCounter(SearchOutcome.TIMEOUT).increment();
			log.warn("[검색] 쿼리 타임아웃 keyword={}", keyword);
			throw new StoreSearchException(StoreSearchErrorCode.SEARCH_TIMEOUT);
		}
	}

	/**
	 * 검색 1건의 구조화 추적 로그 — 어떤 유형(지역/가게명/혼합)의 검색어가 몇 건·어떤 순서로 나왔는지.
	 * request_log 에는 응답시간만 남고 결과 내용이 없어, 품질 비교·회귀 확인은 이 줄로 한다.
	 * key=value 로 grep·집계하기 쉽게 한 줄로 남긴다.
	 */
	private void logSearchTrace(String keyword, String correctedKeyword, SearchIntent intent,
			StoreSearchResult result, SearchOutcome outcome, SearchFallbackStage fallbackStage, String keptToken,
			Pageable pageable) {
		if (!log.isInfoEnabled()) {
			return;
		}
		String topHits = result.hits().stream()
			.limit(SEARCH_TRACE_TOP_HITS)
			.map(hit -> hit.storeId() + ":" + hit.matchedFields().stream().map(Enum::name)
				.map(name -> name.substring(0, 1)).reduce("", String::concat))
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		log.info("[검색추적] type={} q=\"{}\" corrected=\"{}\" regionTokens={} nameTokens={} hits={} hasNext={} "
			+ "outcome={} fallback={} stage={} kept=\"{}\" page={} top={}",
			classifyIntent(intent), keyword, correctedKeyword.equals(keyword) ? "" : correctedKeyword,
			intent.regionTokens(),
			nameTokens(intent), result.hits().size(), result.hasNext(), outcome.tagValue(),
			fallbackStage != SearchFallbackStage.NONE, fallbackStage.tagValue(), keptToken, pageable.getPageNumber(),
			topHits);
	}

	// 검색어 유형 — 지역 사전에 잡힌 토큰과 그 외(가게명·빵 단어) 토큰의 유무로 나눈다.
	static String classifyIntent(SearchIntent intent) {
		boolean hasRegion = !intent.regionTokens().isEmpty();
		boolean hasName = !nameTokens(intent).isEmpty();
		if (hasRegion && hasName) {
			return "REGION_AND_NAME";
		}
		if (hasRegion) {
			return "REGION_ONLY";
		}
		if (hasName) {
			return "NAME_ONLY";
		}
		return "UNCLASSIFIED";
	}

	private static List<String> nameTokens(SearchIntent intent) {
		List<String> tokens = new java.util.ArrayList<>(intent.breadTokens());
		tokens.addAll(intent.remainingTokens());
		return tokens;
	}

	// 위도·경도는 둘 다 있거나 둘 다 없어야 하고, 있으면 WGS84 범위 안이어야 한다. 아니면 400.
	static void validateCoordinates(Double latitude, Double longitude) {
		if (latitude == null && longitude == null) {
			return;
		}
		if (latitude == null || longitude == null) {
			throw new IllegalArgumentException("lat 과 lon 은 함께 주어야 합니다");
		}
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new IllegalArgumentException("lat 은 -90~90, lon 은 -180~180 이어야 합니다");
		}
	}

	static Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_SEARCH_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_SEARCH_PAGE_SIZE, pageable.getSort());
	}

	/**
	 * 폴백 파싱이 1차보다 실제로 더 분해했는지.
	 *
	 * <p>빵만 보면 "소금도화"처럼 지역어만 박힌 검색어에서 재검색이 걸리지 않는다.
	 * 1차에서 붙어쓴 토큰을 통째로 두게 되면서 지역 분해도 폴백으로 넘어왔으므로 함께 본다.
	 */
	private boolean isDecomposed(SearchIntent fallbackIntent, SearchIntent intent) {
		return !fallbackIntent.breadTokens().equals(intent.breadTokens())
			|| !fallbackIntent.regionTokens().equals(intent.regionTokens());
	}

	private SearchOutcome resolveSearchOutcome(StoreSearchResult result, SearchIntent intent,
			boolean fallbackApplied) {
		if (result.hits().isEmpty()) {
			return SearchOutcome.EMPTY;
		}
		// 1차가 0건이라 검색어를 깎거나 풀어서 얻은 결과다. 원문과 다르다는 것을 코드가 이미 알고 있다.
		if (fallbackApplied) {
			return SearchOutcome.FALLBACK;
		}
		if (result.queryCovered()) {
			return SearchOutcome.HIT;
		}
		// 사전에 있는 말들만 쓴 검색이면 대조가 정확하므로 실패도 확실하다.
		return isFullyClassified(intent)
			? SearchOutcome.MISMATCH
			: SearchOutcome.MISMATCH_LOOSE;
	}

	/**
	 * 검색어가 전부 지역·빵 사전에 잡혔는지 — 판정 신뢰도를 가르는 기준. 불용어로 걸러진 잔여 토큰은 제외한다
	 * ("성수동 빵집"의 "빵집"을 사전 밖 말로 보면 정상 검색이 실패로 잡힌다).
	 */
	public static boolean isFullyClassified(SearchIntent intent) {
		String normalizedCleanQuery = SearchTextNormalizer.normalize(intent.cleanQuery());
		boolean hasMeaningfulRemaining = intent.remainingTokens().stream()
			.map(SearchTextNormalizer::normalize)
			.filter(token -> !token.isBlank())
			.anyMatch(normalizedCleanQuery::contains);
		if (hasMeaningfulRemaining) {
			return false;
		}
		return !intent.regionTokens().isEmpty() || !intent.breadTokens().isEmpty();
	}

	private void recordSearchOutcome(SearchOutcome outcome, String keyword) {
		searchCounter(outcome).increment();
		if (outcome == SearchOutcome.HIT) {
			return;
		}
		// 검색어는 카디널리티가 무한해 지표 태그로 쓸 수 없다. 원문은 로그와 search_misses에 남긴다.
		log.info("[검색] 입력한 말이 결과에 없음 outcome={} keyword={}", outcome.tagValue(), keyword);
		// 검색은 사용자 요청 경로다. 적재 지연이 응답에 얹히지 않도록 이벤트로 넘겨 비동기로 처리한다.
		eventPublisher.publishEvent(new SearchMissEvent(keyword, outcome, LocalDateTime.now()));
	}

	private Counter searchCounter(SearchOutcome outcome) {
		return searchCounters.computeIfAbsent(outcome, key -> outcomeCounter(TYPE_SEARCH, key));
	}

	private Counter autocompleteCounter(SearchOutcome outcome) {
		return autocompleteCounters.computeIfAbsent(outcome, key -> outcomeCounter(TYPE_AUTOCOMPLETE, key));
	}

	private Counter fallbackCounter(SearchFallbackStage stage) {
		return fallbackCounters.computeIfAbsent(stage, key -> Counter.builder(SEARCH_FALLBACK_METRIC)
			.description("0건 뒤 폴백으로 결과를 낸 검색 수 — 어느 폴백 단계였는지로 분류")
			.tag("stage", key.tagValue())
			.register(meterRegistry));
	}

	private Counter outcomeCounter(String type, SearchOutcome outcome) {
		return Counter.builder(SEARCH_QUERY_METRIC)
			.description("검색 요청 수 — 사용자가 입력한 말이 결과에 실제로 있었는지로 분류")
			.tag("type", type)
			.tag("outcome", outcome.tagValue())
			.register(meterRegistry);
	}

	public List<StoreAutocompleteResponse> autocompleteStores(String query, int size) {
		return autocompleteStores(query, size, null);
	}

	public List<StoreAutocompleteResponse> autocompleteStores(String query, int size,
			List<AutocompleteSuggestionType> types) {
		if (!StringUtils.hasText(query)) {
			return List.of();
		}

		boolean filtered = types != null && !types.isEmpty();
		Set<AutocompleteSuggestionType> allowedTypes = filtered
			? EnumSet.copyOf(types)
			: EnumSet.allOf(AutocompleteSuggestionType.class);

		int normalizedSize = normalizeAutocompleteSize(size);
		// 다 친 검색어가 교정 목록에 있으면 교정된 말로 후보를 뽑는다("서심당" → 성심당 후보). 캐시 키도 교정된 말이라 옛 빈 결과에 안 막힌다
		String autocompleteQuery = searchKeywordCorrector.correctWhole(query);
		// 타입을 거르면 남는 후보가 줄어드니, 걸러낼 때는 항상 최대치로 가져와 자른다.
		int fetchSize = filtered ? MAX_AUTOCOMPLETE_SIZE : normalizedSize;
		List<StoreAutocompleteSuggestion> suggestions;
		try {
			suggestions = storeSearchEngine.autocompleteStores(autocompleteQuery, fetchSize);
		} catch (QueryTimeoutException exception) {
			// 추천어는 없어도 되는 기능이다 — 500 대신 빈 목록으로 조용히 물러난다. 예외는 엔진에서 던지므로 Redis 캐시가 빈 결과를 담지 않는다.
			autocompleteCounter(SearchOutcome.TIMEOUT).increment();
			log.warn("[자동완성] 쿼리 타임아웃 keyword={}", query);
			return List.of();
		}
		Map<String, StoreAutocompleteSuggestion> uniqueSuggestions = new LinkedHashMap<>();
		for (StoreAutocompleteSuggestion suggestion : suggestions) {
			if (!allowedTypes.contains(suggestion.type())) {
				continue;
			}
			// 지역은 이름이 같아도 다른 곳이다 — "중동"만 열세 곳이라 부모까지 넣어야 따로 선다.
			// 부모 없이 묶으면 사용자가 고를 수 있는 중동이 하나뿐이게 된다.
			String key = suggestion.type() + ":" + suggestion.keyword()
				+ (suggestion.parentName() != null ? ":" + suggestion.parentName() : "");
			uniqueSuggestions.putIfAbsent(key, suggestion);
		}
		List<StoreAutocompleteSuggestion> ordered = new ArrayList<>(uniqueSuggestions.values());
		ordered.sort((left, right) -> Integer.compare(autocompletePriority(left.type()),
			autocompletePriority(right.type())));
		if (allowedTypes.contains(AutocompleteSuggestionType.STORE)) {
			promoteTrendingOrSteady(SearchTextNormalizer.normalize(autocompleteQuery), ordered);
		}
		List<StoreAutocompleteResponse> responses = ordered.stream()
			.limit(normalizedSize)
			.map(StoreAutocompleteResponse::from)
			.toList();
		autocompleteCounter(responses.isEmpty() ? SearchOutcome.EMPTY : SearchOutcome.HIT).increment();
		if (responses.isEmpty()) {
			log.info("[자동완성] 결과 없음 keyword={}", query);
		}
		return responses;
	}

	private int normalizeAutocompleteSize(int size) {
		if (size <= 0) {
			return DEFAULT_AUTOCOMPLETE_SIZE;
		}
		return Math.min(size, MAX_AUTOCOMPLETE_SIZE);
	}

	/**
	 * 디저트는 유행이 빨라 '한'을 치면 지금 뜨는 가게가 지역보다 먼저 보여야 한다. 검색어로 시작하는 급부상 가게가 있으면 그 가게들을, 없으면
	 * 요즘 꾸준히 찾는 가게(평소 수준 1위) 하나를 맨 앞에 둔다. 나머지 순서는 그대로다.
	 */
	private void promoteTrendingOrSteady(String normalizedQuery, List<StoreAutocompleteSuggestion> ordered) {
		if (!storeTrendingProperties.isEnabled()) {
			return;
		}
		List<TrendingStore> trending = trendingRankingReader.findStartingWith(normalizedQuery,
			storeTrendingProperties.getMaxPromoted());
		if (!trending.isEmpty()) {
			Set<Long> promotedIds = trending.stream().map(TrendingStore::storeId).collect(Collectors.toSet());
			// 목록에 이미 있으면(가게명·별칭 행) 빼고 맨 앞에 한 번만 둔다. 목록에 없던 가게도 끼워 넣는다 — 캐시가 옛 목록이어도 뜬다
			ordered.removeIf(suggestion -> suggestion.type() == AutocompleteSuggestionType.STORE
				&& promotedIds.contains(suggestion.storeId()));
			ordered.addAll(0, trending.stream()
				.map(store -> new StoreAutocompleteSuggestion(store.name(), AutocompleteSuggestionType.STORE,
					store.storeId()))
				.toList());
			return;
		}
		for (int index = 0; index < ordered.size(); index++) {
			if (ordered.get(index).isSteadyCandidate(storeTrendingProperties.getSteadyMinBaseline())) {
				// 엔진이 가게를 평소 수준 순으로 세워 두므로 처음 만나는 접두 일치 가게가 스테디 1위다
				ordered.add(0, ordered.remove(index));
				return;
			}
		}
	}

	private int autocompletePriority(AutocompleteSuggestionType type) {
		return switch (type) {
			case REGION -> 0;
			case BREAD_TYPE -> 1;
			case MENU -> 2;
			case STORE -> 3;
		};
	}
}
