package sweetgirl.croissant.store.service.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.enums.MatchedField;
import sweetgirl.croissant.store.repository.search.StoreAutocompleteRow;
import sweetgirl.croissant.store.repository.search.StoreSearchJdbcRepository;
import sweetgirl.croissant.store.repository.search.StoreSearchRow;
import sweetgirl.croissant.store.service.search.StoreSearchSqlBuilder.SqlQuery;

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

	private final StoreSearchSqlBuilder sqlBuilder;
	private final StoreSearchJdbcRepository storeSearchJdbcRepository;

	// 검색 API 지연에서 엔진(SQL 실행+매핑) 몫만 따로 본다. RDB 조립(StoreSearchAssembler)은 별도.
	@Timed(value = "search.engine.query", histogram = true)
	public StoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		StoreSearchPlan plan = toPlan(intent, false);
		if (plan.isEmpty()) {
			return StoreSearchResult.empty();
		}
		StoreSearchResult result = execute(plan, pageable, latitude, longitude, sort);
		if (!result.hits().isEmpty() || pageable.getPageNumber() > 0) {
			return result;
		}
		// 폴백: 사전에 없는 지역어("천안시 서북구")는 주소에만 남아 있다. 1차 0건일 때만 주소 부분일치로 한 번 더.
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
		SqlQuery query = sqlBuilder.buildSearch(plan, pageSize + 1, (int)pageable.getOffset(), latitude, longitude,
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

	@Timed(value = "search.engine.query", histogram = true)
	public List<StoreAutocompleteSuggestion> autocompleteStores(String query, int size) {
		String normalizedQuery = SearchTextNormalizer.normalize(query);
		if (normalizedQuery.isBlank()) {
			return List.of();
		}
		SqlQuery sqlQuery = sqlBuilder.buildAutocomplete(normalizedQuery, size);
		List<StoreAutocompleteRow> rows = storeSearchJdbcRepository.autocomplete(sqlQuery);
		return rows.stream()
			.sorted(Comparator.comparingInt(StoreAutocompleteRow::tier)
				.thenComparing(StoreAutocompleteRow::popularityScore, Comparator.reverseOrder()))
			.map(row -> new StoreAutocompleteSuggestion(row.keyword(), AutocompleteSuggestionType.valueOf(row.type()),
				row.storeId()))
			.toList();
	}
}
