package sweetgirl.croissant.store.service.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.enums.MatchedField;
import sweetgirl.croissant.store.repository.search.SearchSqlQuery;
import sweetgirl.croissant.store.repository.search.StoreAutocompleteRow;
import sweetgirl.croissant.store.repository.search.StoreSearchJdbcRepository;
import sweetgirl.croissant.store.repository.search.StoreSearchRow;
import sweetgirl.croissant.store.repository.search.StoreSearchTokenCountRow;
import sweetgirl.croissant.store.service.search.cache.StoreAutocompleteCache;

/**
 * 가게 검색 엔진 — PostgreSQL(pg_bigm) 부분일치 + PostGIS 거리. 가게명·구명·지역·주소만 본다(메뉴·카테고리 검색 없음).
 *
 * <p>파서({@link StoreSearchQueryParser})가 분류한 토큰을 {@link StoreSearchPlan}으로 옮긴다: 지역 토큰은 지역 조건,
 * 빵 단어·잔여 토큰은 모두 "가게명/구명 부분일치" 토큰. 1차가 0건이면 주소 부분일치로 한 번 더 찾는다(사전에 없는 지역어 흡수).
 * 결과는 가게 ID·점수뿐이고, 응답에 필요한 실제 데이터는 {@link StoreSearchAssembler}가 RDB 에서 채운다.
 *
 * <p>DB 예외는 삼키지 않는다 — RDB 장애는 앱 전체 장애라 숨길 이유가 없고 숨기면 원인을 늦게 안다.
 * (2026-08 OpenSearch 에서 이관. 이관 근거·실측은 .claude/planning/feat-search-postgres-bigm.md)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreSearchEngine {

	// 잔여 토큰(가게명 성분)을 필터로 쓸 최소 길이 — "성수점"→"점" 같은 분해 노이즈 배제
	private static final int MIN_NAME_TOKEN_LENGTH = 2;
	// 토큰 완화 폴백에서 한 토큰만 남길 때 허용하는 후보 가게 수 상한. 운영 14일 검색 재생(2026-10)에서 0건이던 다토큰 검색 202건 중
	// 132건이 단독 결과 15건 미만인 토큰을 가졌다 — 기본 한 페이지(15건)를 넘기는 토큰은 '베이커리' 같은 일반어라 남기지 않는다.
	static final int RELAXED_TOKEN_MAX_CANDIDATES = 14;
	private static final Pattern NUMERIC_ONLY = Pattern.compile("[0-9]+");

	private final StoreSearchSqlBuilder sqlBuilder;
	private final StoreSearchJdbcRepository storeSearchJdbcRepository;
	private final StoreAutocompleteCache storeAutocompleteCache;

	// 검색 API 지연에서 엔진(SQL 실행+매핑) 몫만 따로 본다. RDB 조립(StoreSearchAssembler)은 별도.
	@Timed(value = "search.engine.query", histogram = true)
	public StoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		StoreSearchPlan plan = toPlan(intent, false);
		if (plan.isEmpty()) {
			return StoreSearchResult.empty();
		}
		StoreSearchResult result = execute(plan, pageable, latitude, longitude, sort);
		if (!result.hits().isEmpty()) {
			return result;
		}
		// 폴백: 사전에 없는 지역어("천안시 서북구")는 주소에만 남아 있다. 1차 0건일 때 주소 부분일치로 한 번 더.
		// 페이지 번호로 가드하지 않는다 — 1차가 0건인 검색어는 모든 페이지가 폴백 결과셋이어야 2페이지 이후가 비지 않는다.
		// hasNext 는 size+1 조회로 정확하므로 클라이언트가 N페이지를 요청했다면 1차에 N페이지가 있거나(폴백 안 탐)
		// 처음부터 폴백 결과였거나 둘 중 하나라 결과셋이 섞이지 않는다.
		StoreSearchPlan addressPlan = toPlan(intent, true);
		if (addressPlan.isEmpty()) {
			return result;
		}
		StoreSearchResult addressResult = execute(addressPlan, pageable, latitude, longitude, sort);
		if (!addressResult.hits().isEmpty()) {
			log.debug("[검색] 주소 폴백 적중 query='{}' hits={}", intent.rawQuery(), addressResult.hits().size());
		}
		return addressResult;
	}

	/**
	 * 토큰 완화 폴백 — 1차·주소·붙여 쓴 토큰 분해까지 전부 0건일 때만 부른다. 결과가 있던 검색은 이 경로를 타지 않는다.
	 * <ol>
	 *   <li>어순 무관 이름 매칭: 지역으로 분류된 토큰도 이름 토큰으로 보고, 모든 토큰이 이름/구명에 든 가게를 찾는다.
	 *       "성수 어니언"의 '성수'가 지역으로 떨어져도 가게 "어니언 성수"가 잡힌다.</li>
	 *   <li>그래도 0건이면 가장 구체적인(후보가 가장 적은) 토큰 하나만 남긴다. 남길 만한 토큰이 없으면 완화하지 않는다.</li>
	 * </ol>
	 *
	 * @param intents 1차 인텐트가 맨 앞, 붙여 쓴 토큰을 분해한 인텐트가 있으면 그 뒤. 한 토큰 남기기는 사용자가 띄어 쓴 1차 토큰으로만 한다
	 *                (분해 조각은 시스템이 만든 것이라 하나만 남기면 엉뚱한 가게가 나온다).
	 */
	@Timed(value = "search.engine.query", histogram = true)
	public Optional<StoreSearchRelaxedResult> searchStoresRelaxed(List<SearchIntent> intents, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		if (intents.isEmpty()) {
			return Optional.empty();
		}
		// 2페이지 이후의 0건은 "결과가 없는 검색"이 아니라 "결과의 끝을 넘긴 요청"일 수 있다. 완화 없는 검색이 첫 페이지부터
		// 0건일 때만 완화해, 결과가 있던 검색의 끝 뒤에 완화 결과가 이어 붙지 않게 한다.
		if (pageable.getPageNumber() > 0 && hasResultWithoutRelaxation(intents, latitude, longitude, sort)) {
			return Optional.empty();
		}
		Set<StoreSearchPlan> triedPlans = new LinkedHashSet<>();
		for (SearchIntent intent : intents) {
			Optional<StoreSearchPlan> anyOrderPlan = toAnyOrderNamePlan(intent);
			if (anyOrderPlan.isEmpty() || !triedPlans.add(anyOrderPlan.get())) {
				continue;
			}
			StoreSearchResult result = execute(anyOrderPlan.get(), pageable, latitude, longitude, sort);
			if (!result.hits().isEmpty()) {
				return Optional.of(new StoreSearchRelaxedResult(result, SearchFallbackStage.NAME_ANY_ORDER, ""));
			}
		}
		Optional<String> keptToken = selectMostSpecificToken(intents.get(0));
		if (keptToken.isEmpty()) {
			return Optional.empty();
		}
		// 남긴 토큰이 이름의 맨 앞에 오는 가게만 — 후보 수를 센 조건과 같다
		StoreSearchPlan singleTokenPlan = new StoreSearchPlan(List.of(), List.of(keptToken.get()), keptToken.get(),
			false, true);
		StoreSearchResult result = execute(singleTokenPlan, pageable, latitude, longitude, sort);
		if (result.hits().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new StoreSearchRelaxedResult(result, SearchFallbackStage.SINGLE_TOKEN, keptToken.get()));
	}

	private boolean hasResultWithoutRelaxation(List<SearchIntent> intents, Double latitude, Double longitude,
			SearchSort sort) {
		Pageable firstRow = PageRequest.of(0, 1);
		return intents.stream()
			.anyMatch(intent -> !searchStores(intent, firstRow, latitude, longitude, sort).hits().isEmpty());
	}

	/**
	 * 어순 무관 이름 매칭 계획 — 지역·빵·잔여 토큰을 모두 이름 토큰(AND)으로 둔다. 지역 토큰이 없으면 1차와 같은 조건이라 만들지 않는다.
	 */
	Optional<StoreSearchPlan> toAnyOrderNamePlan(SearchIntent intent) {
		if (intent.regionTokens().isEmpty()) {
			return Optional.empty();
		}
		List<String> tokens = normalizedDistinct(allTokens(intent), MIN_NAME_TOKEN_LENGTH);
		if (tokens.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(new StoreSearchPlan(List.of(), tokens,
			SearchTextNormalizer.normalize(intent.cleanQuery()), false));
	}

	/**
	 * 한 토큰만 남긴다면 어느 토큰인지. 후보 가게(이름/구명이 그 토큰으로 시작) 수가 1 이상 {@link #RELAXED_TOKEN_MAX_CANDIDATES} 이하인 토큰 중 가장 적은 것을
	 * 고르고(같으면 긴 토큰, 그다음 앞선 토큰), 그런 토큰이 없으면 비운다 — 넓은 토큰만 남는 검색은 완화하지 않는다.
	 */
	Optional<String> selectMostSpecificToken(SearchIntent intent) {
		// 토큰이 하나뿐이면 버릴 것이 없다 — 그 토큰만으로 다시 찾아도 1차와 같은 검색이다.
		if (normalizedDistinct(allTokens(intent), MIN_NAME_TOKEN_LENGTH).size() < 2) {
			return Optional.empty();
		}
		List<String> tokens = relaxableTokens(intent);
		if (tokens.isEmpty()) {
			return Optional.empty();
		}
		List<StoreSearchTokenCountRow> counts = storeSearchJdbcRepository.countTokenCandidates(
			sqlBuilder.buildTokenCandidateCounts(tokens, RELAXED_TOKEN_MAX_CANDIDATES + 1));
		return counts.stream()
			.filter(count -> count.tokenIndex() >= 0 && count.tokenIndex() < tokens.size())
			.filter(count -> count.candidateCount() > 0 && count.candidateCount() <= RELAXED_TOKEN_MAX_CANDIDATES)
			.min(Comparator.comparingLong(StoreSearchTokenCountRow::candidateCount)
				.thenComparing(count -> tokens.get(count.tokenIndex()).length(), Comparator.reverseOrder())
				.thenComparingInt(StoreSearchTokenCountRow::tokenIndex))
			.map(count -> tokens.get(count.tokenIndex()));
	}

	/**
	 * 하나만 남겨도 되는 토큰. 지역 토큰은 뺀다 — 지역명만 남기면 이름에 그 지역명이 든 엉뚱한 가게가 나온다. 불용어("맛집")도
	 * 뺀다(cleanQuery 에 남지 않은 잔여 토큰). 숫자뿐인 토큰("396", 번지 "489")도 뺀다 — 가게를 가리키는 말이 아니라 이름에 든
	 * 숫자에 우연히 걸린다.
	 */
	private List<String> relaxableTokens(SearchIntent intent) {
		Set<String> cleanTokens = new LinkedHashSet<>(normalizedDistinct(List.of(intent.cleanQuery().split(" ")), 1));
		List<String> tokens = new ArrayList<>(intent.breadTokens());
		for (String token : intent.remainingTokens()) {
			if (cleanTokens.contains(SearchTextNormalizer.normalize(token))) {
				tokens.add(token);
			}
		}
		return normalizedDistinct(tokens, MIN_NAME_TOKEN_LENGTH).stream()
			.filter(token -> !NUMERIC_ONLY.matcher(token).matches())
			.toList();
	}

	private List<String> allTokens(SearchIntent intent) {
		List<String> tokens = new ArrayList<>(intent.regionTokens());
		tokens.addAll(intent.breadTokens());
		tokens.addAll(intent.remainingTokens());
		return tokens;
	}

	StoreSearchPlan toPlan(SearchIntent intent, boolean addressFallback) {
		List<String> regionTokens = normalizedDistinct(intent.regionTokens(), 1);
		List<String> nameTokens = new ArrayList<>();
		nameTokens.addAll(intent.breadTokens());
		nameTokens.addAll(intent.remainingTokens());
		return new StoreSearchPlan(
			regionTokens,
			normalizedDistinct(nameTokens, MIN_NAME_TOKEN_LENGTH),
			SearchTextNormalizer.normalize(intent.cleanQuery()),
			addressFallback);
	}

	private List<String> normalizedDistinct(List<String> tokens, int minLength) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String token : tokens) {
			String value = SearchTextNormalizer.normalize(token);
			if (value.length() >= minLength) {
				normalized.add(value);
			}
		}
		return List.copyOf(normalized);
	}

	private StoreSearchResult execute(StoreSearchPlan plan, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		int pageSize = pageable.getPageSize();
		// 다음 페이지 유무를 정확히 알기 위해 한 건 더 읽는다(hits.size()==pageSize 추정은 마지막 페이지에서 틀린다).
		SearchSqlQuery query = sqlBuilder.buildSearch(plan, pageSize + 1, (int)pageable.getOffset(), latitude,
			longitude,
			sort);
		List<StoreSearchRow> rows = storeSearchJdbcRepository.search(query);
		boolean hasNext = rows.size() > pageSize;
		List<StoreSearchRow> pageRows = hasNext ? rows.subList(0, pageSize) : rows;

		List<StoreSearchHit> hits = new ArrayList<>(pageRows.size());
		boolean queryCovered = false;
		for (StoreSearchRow row : pageRows) {
			hits.add(new StoreSearchHit(row.storeId(), row.score(), row.distanceMeters(), List.of(),
				matchedFields(row, plan)));
			queryCovered = queryCovered || covers(row, plan);
		}
		return new StoreSearchResult(hits, hasNext, queryCovered);
	}

	private List<MatchedField> matchedFields(StoreSearchRow row, StoreSearchPlan plan) {
		Set<MatchedField> fields = EnumSet.noneOf(MatchedField.class);
		// 이름 토큰이 있는 계획(INTERSECT)의 후보는 구성상 모든 토큰이 이름/구명에 있다 — 점수 0이어도 이름 매칭이다.
		if (row.matchedByName() || (plan.hasNameTokens() && !plan.addressFallback())) {
			fields.add(MatchedField.STORE_NAME);
		}
		if (row.matchedByRegion() || plan.addressFallback()) {
			fields.add(MatchedField.REGION);
		}
		return List.copyOf(fields);
	}

	/**
	 * 품질 지표용 커버리지 — "입력한 말이 결과에 있는가".
	 * 주소 폴백 적중, 검색어(전체·토큰)가 이름/구명에 있음, 이름 토큰이 전부 있고(구성상 보장) 지역 토큰도 매핑에 있음, 지역만 검색에서
	 * 지역 매핑에 있음 → 커버. 지역만 검색에서 이름 OR 가지로 들어온 가게는 이름 점수로 판정된다.
	 */
	private boolean covers(StoreSearchRow row, StoreSearchPlan plan) {
		if (plan.addressFallback()) {
			return true;
		}
		boolean regionSatisfied = !plan.hasRegionTokens() || row.matchedByRegion();
		if (plan.hasNameTokens()) {
			return regionSatisfied;
		}
		if (row.matchedByName()) {
			return true;
		}
		return plan.hasRegionTokens() && row.matchedByRegion();
	}

	/**
	 * Redis 캐시(검색어·size 별)를 먼저 본다 — 글자마다 부르는 자동완성은 같은 검색어가 반복된다. 캐시 키는 정규화한 검색어라 "성심 당"과
	 * "성심당"이 같은 결과를 나눠 쓴다. 지표(search.engine.query)는 캐시 적중까지 포함한 응답 시간이다 — 캐시 전후를 같은 눈금으로 본다.
	 */
	@Timed(value = "search.engine.query", histogram = true)
	public List<StoreAutocompleteSuggestion> autocompleteStores(String query, int size) {
		String normalizedQuery = SearchTextNormalizer.normalize(query);
		if (normalizedQuery.isBlank()) {
			return List.of();
		}
		return storeAutocompleteCache.getOrLoad(normalizedQuery, size, () -> queryAutocomplete(normalizedQuery, size));
	}

	private List<StoreAutocompleteSuggestion> queryAutocomplete(String normalizedQuery, int size) {
		SearchSqlQuery sqlQuery = sqlBuilder.buildAutocomplete(normalizedQuery, size);
		List<StoreAutocompleteRow> rows = storeSearchJdbcRepository.autocomplete(sqlQuery);
		return rows.stream()
			// 접두 일치 → 요즘 꾸준히 찾는 가게(평소 수준) → 누적 인기도
			.sorted(Comparator.comparingInt(StoreAutocompleteRow::tier)
				.thenComparing(StoreAutocompleteRow::steadyScore, Comparator.reverseOrder())
				.thenComparing(StoreAutocompleteRow::popularityScore, Comparator.reverseOrder()))
			.map(row -> new StoreAutocompleteSuggestion(row.keyword(), AutocompleteSuggestionType.valueOf(row.type()),
				row.storeId(), row.centerLat(), row.centerLng(), row.radiusMeters(), row.parentName(), row.tier(),
				row.steadyScore()))
			.toList();
	}
}
