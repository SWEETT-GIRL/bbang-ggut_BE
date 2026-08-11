package sweetgirl.croissant.store.service.search;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import sweetgirl.croissant.external.opensearch.client.OpenSearchStoreClient;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchAutocompleteSuggestion;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
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

	private static final int DEFAULT_AUTOCOMPLETE_SIZE = 10;
	private static final int MAX_AUTOCOMPLETE_SIZE = 20;
	private static final String SEARCH_QUERY_METRIC = "search.query";
	private static final String TYPE_SEARCH = "search";
	private static final String TYPE_AUTOCOMPLETE = "autocomplete";

	private final StoreSearchQueryParser storeSearchQueryParser;
	private final OpenSearchStoreClient openSearchStoreClient;
	private final StoreSearchAssembler storeSearchAssembler;
	private final SearchQueryCoverage searchQueryCoverage;
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
		// 자동완성은 후보 문자열 자체를 OpenSearchStoreClient에서 이미 검증하므로 hit/empty만 발생한다.
		autocompleteCounter(SearchOutcome.HIT);
		autocompleteCounter(SearchOutcome.EMPTY);
	}

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Double latitude, Double longitude,
			SearchSort sort, Pageable pageable) {
		// 1차: 가게명 우선 — "브릭베이글"처럼 붙여쓴 토큰은 통째로 가게명/메뉴 검색
		SearchIntent intent = storeSearchQueryParser.parse(keyword);
		OpenSearchStoreSearchResult result = openSearchStoreClient.searchStores(intent, pageable, latitude, longitude,
			sort);
		// 폴백: 1차가 0건이고, 붙어쓴 토큰 속 빵 단어를 분해할 수 있으면("초코소금빵"→"소금빵" 메뉴 필터)
		// 해당 메뉴를 가진 가게를 다시 검색한다. 빵 분해 결과가 1차와 같으면(분해할 빵이 없으면) 재검색하지 않는다.
		boolean fallbackApplied = false;
		if (result.hits().isEmpty()) {
			SearchIntent fallbackIntent = storeSearchQueryParser.parseWithBreadFallback(keyword);
			if (!fallbackIntent.breadTokens().equals(intent.breadTokens())) {
				result = openSearchStoreClient.searchStores(fallbackIntent, pageable, latitude, longitude, sort);
				fallbackApplied = !result.hits().isEmpty();
			}
		}
		recordSearchOutcome(resolveSearchOutcome(result, intent, fallbackApplied), keyword);
		if (result.hits().isEmpty()) {
			return new SliceResponse<>(List.of(), false);
		}
		return new SliceResponse<>(storeSearchAssembler.assemble(result.hits()), result.hasNext());
	}

	private SearchOutcome resolveSearchOutcome(OpenSearchStoreSearchResult result, SearchIntent intent,
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
		return searchQueryCoverage.isFullyClassified(intent)
			? SearchOutcome.MISMATCH
			: SearchOutcome.MISMATCH_LOOSE;
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
		if (!StringUtils.hasText(query)) {
			return List.of();
		}

		int normalizedSize = normalizeAutocompleteSize(size);
		List<OpenSearchAutocompleteSuggestion> suggestions = openSearchStoreClient.autocompleteStores(query,
			normalizedSize);
		Map<String, StoreAutocompleteResponse> uniqueSuggestions = new LinkedHashMap<>();
		for (OpenSearchAutocompleteSuggestion suggestion : suggestions) {
			StoreAutocompleteResponse response = StoreAutocompleteResponse.from(suggestion);
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
