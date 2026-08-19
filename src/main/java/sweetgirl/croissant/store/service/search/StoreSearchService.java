package sweetgirl.croissant.store.service.search;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
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
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;

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
	private static final String TYPE_SEARCH = "search";
	private static final String TYPE_AUTOCOMPLETE = "autocomplete";
	private static final int SEARCH_TRACE_TOP_HITS = 10;

	private final StoreSearchQueryParser storeSearchQueryParser;
	private final StoreSearchEngine storeSearchEngine;
	private final StoreSearchAssembler storeSearchAssembler;
	private final MeterRegistry meterRegistry;
	private final ApplicationEventPublisher eventPublisher;

	private final Map<SearchOutcome, Counter> searchCounters = new EnumMap<>(SearchOutcome.class);
	private final Map<SearchOutcome, Counter> autocompleteCounters = new EnumMap<>(SearchOutcome.class);

	// Counter는 최초 증가 전까지 /actuator/prometheus에 노출되지 않는다. 대시보드에서 "0건"과
	// "계측이 안 됨"을 구분할 수 있도록 부팅 시 태그 조합을 미리 등록해 0부터 그려지게 한다.
	@PostConstruct
	void registerSearchOutcomeCounters() {
		for (SearchOutcome outcome : SearchOutcome.values()) {
			searchCounter(outcome);
		}
		// 자동완성은 후보 문자열 자체를 엔진에서 이미 검증하므로 hit/empty만 발생한다.
		autocompleteCounter(SearchOutcome.HIT);
		autocompleteCounter(SearchOutcome.EMPTY);
	}

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Double latitude, Double longitude,
			SearchSort sort, Pageable pageable) {
		// 1차: 가게명 우선 — "브릭베이글"·"소금도화"처럼 붙여쓴 토큰은 통째로 가게명 검색
		SearchIntent intent = storeSearchQueryParser.parse(keyword);
		StoreSearchResult result = storeSearchEngine.searchStores(intent, pageable, latitude, longitude, sort);
		// 폴백: 1차가 0건일 때만 붙어쓴 토큰을 분해해 다시 검색한다
		// ("초코소금빵"→빵 "소금빵", "한남에그타르트"→지역 "한남" + 빵 "에그타르트").
		// 지역·빵 어느 쪽도 새로 분해되지 않으면 같은 쿼리가 되므로 재검색하지 않는다.
		boolean fallbackApplied = false;
		if (result.hits().isEmpty()) {
			SearchIntent fallbackIntent = storeSearchQueryParser.parseWithGluedTokenFallback(keyword);
			if (isDecomposed(fallbackIntent, intent)) {
				result = storeSearchEngine.searchStores(fallbackIntent, pageable, latitude, longitude, sort);
				fallbackApplied = !result.hits().isEmpty();
			}
		}
		// 2페이지 이후는 같은 검색의 연속이므로 세지 않는다. 무한스크롤이 검색 API를 페이지마다
		// 호출하는데, 이걸 다 세면 "많이 스크롤한 검색어"가 "여러 사람이 찾은 검색어"보다 위로 올라와
		// 실패 목록의 우선순위가 뒤집힌다. 실제로 "천안시 서북구"가 검색 5회 + 스크롤 6페이지로
		// 11회가 되어, 2회인 다른 검색어보다 높게 잡혔다.
		SearchOutcome outcome = resolveSearchOutcome(result, intent, fallbackApplied);
		if (pageable.getPageNumber() == FIRST_PAGE) {
			recordSearchOutcome(outcome, keyword);
		}
		logSearchTrace(keyword, intent, result, outcome, fallbackApplied, pageable);
		if (result.hits().isEmpty()) {
			return new SliceResponse<>(List.of(), false);
		}
		return new SliceResponse<>(storeSearchAssembler.assemble(result.hits()), result.hasNext());
	}

	/**
	 * 검색 1건의 구조화 추적 로그 — 어떤 유형(지역/가게명/혼합)의 검색어가 몇 건·어떤 순서로 나왔는지.
	 * request_log 에는 응답시간만 남고 결과 내용이 없어, 품질 비교·회귀 확인은 이 줄로 한다.
	 * key=value 로 grep·집계하기 쉽게 한 줄로 남긴다.
	 */
	private void logSearchTrace(String keyword, SearchIntent intent,
			StoreSearchResult result, SearchOutcome outcome, boolean fallbackApplied, Pageable pageable) {
		if (!log.isInfoEnabled()) {
			return;
		}
		String topHits = result.hits().stream()
			.limit(SEARCH_TRACE_TOP_HITS)
			.map(hit -> hit.storeId() + ":" + hit.matchedFields().stream().map(Enum::name)
				.map(name -> name.substring(0, 1)).reduce("", String::concat))
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		log.info("[검색추적] type={} q=\"{}\" regionTokens={} nameTokens={} hits={} hasNext={} outcome={} "
			+ "fallback={} page={} top={}",
			classifyIntent(intent), keyword, intent.regionTokens(),
			nameTokens(intent), result.hits().size(), result.hasNext(), outcome.tagValue(),
			fallbackApplied, pageable.getPageNumber(), topHits);
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
		// 1차가 0건이라 검색어를 깎아서 얻은 결과다. 원문과 다르다는 것을 코드가 이미 알고 있다.
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
	static boolean isFullyClassified(SearchIntent intent) {
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
		// 타입을 거르면 남는 후보가 줄어드니, 걸러낼 때는 항상 최대치로 가져와 자른다.
		int fetchSize = filtered ? MAX_AUTOCOMPLETE_SIZE : normalizedSize;
		List<StoreAutocompleteSuggestion> suggestions = storeSearchEngine.autocompleteStores(query, fetchSize);
		Map<String, StoreAutocompleteResponse> uniqueSuggestions = new LinkedHashMap<>();
		for (StoreAutocompleteSuggestion suggestion : suggestions) {
			StoreAutocompleteResponse response = StoreAutocompleteResponse.from(suggestion);
			if (!allowedTypes.contains(response.type())) {
				continue;
			}
			uniqueSuggestions.putIfAbsent(response.type() + ":" + response.keyword(), response);
		}
		List<StoreAutocompleteResponse> responses = uniqueSuggestions.values().stream()
			.sorted((left, right) -> Integer.compare(autocompletePriority(left), autocompletePriority(right)))
			.limit(normalizedSize)
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

	private int autocompletePriority(StoreAutocompleteResponse response) {
		return switch (response.type()) {
			case REGION -> 0;
			case BREAD_TYPE -> 1;
			case MENU -> 2;
			case STORE -> 3;
		};
	}
}
