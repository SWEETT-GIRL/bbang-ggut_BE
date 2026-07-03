package sweetgirl.croissant.external.opensearch.query;

import static sweetgirl.croissant.external.opensearch.document.StoreDocumentConstants.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.DistanceUnit;
import org.opensearch.client.opensearch._types.FieldSort;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.GeoDistanceSort;
import org.opensearch.client.opensearch._types.GeoLocation;
import org.opensearch.client.opensearch._types.ScoreSort;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.FieldValueFactorModifier;
import org.opensearch.client.opensearch._types.query_dsl.FunctionBoostMode;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScore;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScoreMode;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.search.Highlight;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.opensearch.code.SearchRankingConstants;
import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchSort;

@Component
@RequiredArgsConstructor
public class StoreSearchQueryBuilder {

	private static final int INNER_HITS_SIZE = 5;
	private static final String MINIMUM_SHOULD_MATCH = "1";
	// 잔여 토큰(가게명/메뉴 성분)을 가게명 must 필터로 쓸 최소 길이. "성수점"→"점" 같은 분해 노이즈 배제.
	private static final int MIN_REMAINING_MUST_LENGTH = 2;

	private final OpenSearchProperties openSearchProperties;

	/*
	 * 케이스별 쿼리 전략:
	 *
	 * [케이스 1] 지역 + 빵 키워드 둘 다 있을 때 — "한남 에그타르트"
	 *   must: [지역 매칭, 빵 매칭]  → 둘 다 만족하는 가게만 결과에 포함
	 *   should: [원문 매칭]         → 가게명/메뉴명이 원문과 유사하면 점수 보너스
	 *
	 * [케이스 2] 지역 키워드만 있을 때 — "한남"
	 *   must: []
	 *   should: [지역 매칭, 원문 매칭]  → minimum_should_match=1, 지역 근처 가게 노출
	 *
	 * [케이스 2-1] 지역 + 가게명/잔여 키워드 — "서울 테디뵈르하우스", "망원 브릭베"
	 *   must: [지역 매칭, 잔여 매칭(storeName OR menus.name)]  → 그 지역에서 그 이름/메뉴 가진 가게만
	 *   should: [원문 매칭]         → 정확 가게명 등 점수 보너스
	 *   → 잔여를 must로 걸어 "서울 전체"가 아니라 "서울의 테디뵈르하우스"만 남긴다(flood 제거)
	 *   → 길이<2 잔여("성수점"→"점")는 분해 노이즈이므로 must에서 제외
	 *
	 * [케이스 3] 빵 키워드만 있을 때 — "에그타르트"
	 *   must: [빵 매칭]             → 에그타르트 있는 가게만 결과에 포함
	 *   should: [원문 매칭]         → 가게명/메뉴명 유사도로 점수 보정
	 *   → 지역어가 없으므로 sortByDistance=true면 거리순 정렬로 사용자 주변 가게 우선 노출
	 *
	 * [케이스 4] 아무 토큰도 없을 때 — (파싱 실패 또는 짧은 단어)
	 *   must: []
	 *   should: [원문 매칭]         → minimum_should_match=1, 원문으로 가게명/메뉴 전체 검색
	 *
	 * 공통: function_score로 인기도(popularityScore) + 거리 감쇠(gauss) 점수 추가
	 *
	 * 필드×부스트는 SearchMatchProfile(매칭 프로파일)·RankingWeights(term/스코어링)에서 데이터로 관리한다.
	 */
	public SearchRequest build(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		return build(intent, pageable, latitude, longitude, sort, false);
	}

	// explain=true면 OpenSearch가 hit별 점수 산출 내역(_explanation)을 응답에 포함
	// 디버그 전용 — 응답 payload가 커지고 스코어링 오버헤드가 있으므로 DEBUG 로깅이 켜진 경우에만 호출
	public SearchRequest build(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort, boolean explain) {
		List<Query> must = new ArrayList<>();
		List<Query> should = new ArrayList<>();
		boolean hasRegionTokens = !intent.regionTokens().isEmpty();
		boolean hasBreadTokens = !intent.breadTokens().isEmpty();
		// 길이<2 잔여("성수점"→"점" 등 분해 노이즈)는 가게명 must 필터로 쓰면 오작동하므로 제외한다.
		List<String> filterableRemainingTokens = intent.remainingTokens().stream()
			.filter(token -> token.length() >= MIN_REMAINING_MUST_LENGTH)
			.toList();
		boolean hasRemainingTokens = !filterableRemainingTokens.isEmpty();

		if (hasRegionTokens) {
			Query regionMatch = boolShould(regionQueries(intent.regionTokens()));
			if (hasBreadTokens || hasRemainingTokens) {
				// 케이스 1, 2-1: 지역 + 다른 검색어(빵/가게명) → 지역을 must로 강제해 "그 지역의 ~"로 좁힘
				must.add(regionMatch);
			} else {
				// 케이스 2: 지역만 → should (거리 감쇠로 주변까지 포함)
				should.add(regionMatch);
			}
		}

		if (hasBreadTokens) {
			// 케이스 1, 3: 빵 키워드는 항상 must (에그타르트 없는 가게는 제외)
			must.add(boolShould(breadQueries(intent.breadTokens())));
		}

		// 잔여 토큰(지역·빵이 아닌 = 가게명/메뉴 성분)은 must — 그 이름/메뉴를 실제 가진 가게만 노출(flood 제거).
		// remainingQuery = (storeName OR menus.name) 부분매칭(msm=1)이라 가게명·메뉴 어느 쪽이든 매칭되면 통과.
		// 여러 토큰이면 각 토큰을 AND로 요구한다("우드 앤 브릭" → 세 토큰 모두 매칭).
		for (int i = 0; i < filterableRemainingTokens.size(); i++) {
			must.add(remainingQuery(filterableRemainingTokens.get(i), "remainingMatchedMenus" + i));
		}

		// 불용어 제거된 쿼리를 가게명/메뉴명에 매칭 — must와 무관하게 항상 should에 포함
		should.add(rawQuery(intent.cleanQuery()));

		Query bool = Query.of(q -> q.bool(b -> {
			b.must(must).should(should);
			if (must.isEmpty()) {
				// 케이스 2, 4: must가 비어있으면 should 중 최소 1개는 매칭돼야 결과에 포함
				b.minimumShouldMatch(MINIMUM_SHOULD_MATCH);
			}
			return b;
		}));

		SearchRequest.Builder request = new SearchRequest.Builder()
			.index(openSearchProperties.getStoresIndex())
			.from((int)pageable.getOffset())
			.size(pageable.getPageSize())
			.explain(explain)
			.query(functionScore(bool, latitude, longitude))
			.highlight(highlight());

		List<SortOptions> sortOptions = sortOptions(sort, latitude, longitude);
		if (!sortOptions.isEmpty()) {
			request.sort(sortOptions);
		}
		return request.build();
	}

	public SearchRequest buildAutocomplete(String query, int size) {
		Query autocompleteQuery = profileQuery(SearchMatchProfile.AUTOCOMPLETE, query, null);

		return new SearchRequest.Builder()
			.index(openSearchProperties.getStoresIndex())
			.size(size)
			.query(functionScore(autocompleteQuery, null, null))
			.build();
	}

	private List<Query> regionQueries(List<String> regionTokens) {
		return regionTokens.stream()
			.map(token -> profileQuery(SearchMatchProfile.REGION, token, null))
			.toList();
	}

	private List<Query> breadQueries(List<String> breadTokens) {
		List<Query> queries = new ArrayList<>();
		for (int i = 0; i < breadTokens.size(); i++) {
			queries.add(profileQuery(SearchMatchProfile.BREAD, breadTokens.get(i), "matchedMenus" + i));
		}
		return queries;
	}

	private Query functionScore(Query bool, Double latitude, Double longitude) {
		List<FunctionScore> functions = new ArrayList<>();
		// baseline — 곱셈(Multiply) 시 인기도/거리 점수가 0이어도 텍스트 점수가 사라지지 않도록 보장
		functions.add(FunctionScore.of(fn -> fn.weight(SearchRankingConstants.BASELINE_WEIGHT)));
		// 인기도는 텍스트 점수를 "덮어쓰지" 않고 완만하게 증폭만 — Log1p로 조회수 폭주 가게의 과도한 보정 방지
		functions.add(FunctionScore.of(fn -> fn.fieldValueFactor(f -> f
			.field(POPULARITY_SCORE)
			.factor(SearchRankingConstants.POPULARITY_FACTOR)
			.modifier(FieldValueFactorModifier.Log1p)
			.missing(SearchRankingConstants.POPULARITY_MISSING))));
		if (latitude != null && longitude != null) {
			Map<String, Object> origin = Map.of("lat", latitude, "lon", longitude);
			functions.add(FunctionScore.of(fn -> fn.gauss(d -> d
				.field(LOCATION)
				.placement(p -> p
					.origin(JsonData.of(origin))
					.scale(JsonData.of(SearchRankingConstants.GAUSS_SCALE))
					.offset(JsonData.of(SearchRankingConstants.GAUSS_OFFSET))
					.decay(SearchRankingConstants.GAUSS_DECAY)))));
		}
		// Multiply: 최종점수 = 텍스트매칭점수 × (1 + 인기도보정 + 거리보정)
		// → 관련 없는 인기 가게가 텍스트 점수를 추월하지 못하고, 정확 매칭 가게가 항상 상위에 노출
		return Query.of(q -> q.functionScore(fs -> fs
			.query(bool)
			.functions(functions)
			.boostMode(FunctionBoostMode.Multiply)
			.scoreMode(FunctionScoreMode.Sum)));
	}

	private Query rawQuery(String query) {
		// 가게명 정확/정규화 일치(term)를 부분 매칭 위로 끌어올린 뒤, 부분 매칭(NAME_MENU_PARTIAL)을 더한다.
		// nori 필드는 제외: nori_part_of_speech가 "후와후와"→"후"로 뭉개 오매칭하므로, keyword 토크나이저
		// 기반 edge/ngram(원문 유지)으로만 부분 매칭한다.
		List<Query> clauses = new ArrayList<>();
		clauses.add(termBoost(SearchSubField.KEYWORD.field(STORE_NAME), query,
			SearchRankingConstants.STORE_NAME_KEYWORD_BOOST));
		clauses.add(termBoost(SearchSubField.EDGE.field(STORE_NAME), normalizeForExactMatch(query),
			SearchRankingConstants.STORE_NAME_EDGE_BOOST));
		clauses.add(termBoost(SearchSubField.NGRAM.field(STORE_NAME), normalizeForExactMatch(query),
			SearchRankingConstants.STORE_NAME_NGRAM_BOOST));
		clauses.addAll(profileClauses(SearchMatchProfile.NAME_MENU_PARTIAL, query, "rawMatchedMenus"));
		return boolShould(clauses);
	}

	private Query remainingQuery(String query, String innerHitsName) {
		// 잔여 토큰은 soft 보너스 — 조사 제거 없는 edge/ngram으로 가게명/메뉴명에 부분 매칭만.
		return profileQuery(SearchMatchProfile.NAME_MENU_PARTIAL, query, innerHitsName);
	}

	// 매칭 프로파일 → OpenSearch 쿼리. flat 필드는 하나의 multi_match로, nested 필드는 path별 nested 쿼리로 묶는다.
	private Query profileQuery(SearchMatchProfile profile, String query, String innerHitsName) {
		return boolShould(profileClauses(profile, query, innerHitsName));
	}

	private List<Query> profileClauses(SearchMatchProfile profile, String query, String innerHitsName) {
		List<Query> clauses = new ArrayList<>();

		List<String> flatFields = profile.fields().stream()
			.filter(field -> !field.isNested())
			.flatMap(field -> field.boostedFields().stream())
			.toList();
		if (!flatFields.isEmpty()) {
			clauses.add(multiMatch(query, flatFields));
		}

		Map<String, List<String>> nestedFieldsByPath = new LinkedHashMap<>();
		for (FieldBoost field : profile.fields()) {
			if (field.isNested()) {
				nestedFieldsByPath.computeIfAbsent(field.nestedPath(), key -> new ArrayList<>())
					.addAll(field.boostedFields());
			}
		}
		nestedFieldsByPath.forEach((path, fields) -> {
			if (MENUS.equals(path) && innerHitsName != null) {
				clauses.add(menuNested(query, innerHitsName, fields));
			} else {
				clauses.add(plainNested(path, query, fields));
			}
		});
		return clauses;
	}

	private Query termBoost(String field, String value, float boost) {
		return Query.of(q -> q.term(t -> t
			.field(field)
			.value(v -> v.stringValue(value))
			.boost(boost)));
	}

	private Query plainNested(String path, String query, List<String> fields) {
		return Query.of(q -> q.nested(n -> n
			.path(path)
			.query(multiMatch(query, fields))));
	}

	private Query menuNested(String query, String innerHitsName, List<String> fields) {
		return Query.of(q -> q.nested(n -> n
			.path(MENUS)
			.query(multiMatch(query, fields))
			.innerHits(ih -> ih
				.name(innerHitsName)
				.size(INNER_HITS_SIZE)
				.source(sc -> sc.filter(f -> f.includes("menus.id", "menus.name"))))));
	}

	private Query multiMatch(String query, List<String> fields) {
		return Query.of(q -> q.multiMatch(m -> m
			.query(query)
			.fields(fields)
			.type(TextQueryType.BestFields)
			.operator(Operator.And)));
	}

	private String normalizeForExactMatch(String query) {
		return query.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-zA-Z가-힣]", "");
	}

	private Query boolShould(List<Query> should) {
		return Query.of(q -> q.bool(b -> b
			.should(should)
			.minimumShouldMatch(MINIMUM_SHOULD_MATCH)));
	}

	private Highlight highlight() {
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		HighlightField empty = HighlightField.of(f -> f);
		fields.put("storeName", empty);
		fields.put("storeName.edge", empty);
		fields.put("storeName.ngram", empty);
		fields.put("searchRegionAliases", empty);
		fields.put("searchRegionAliases.edge", empty);
		fields.put("breadTypes", empty);
		fields.put("breadTypes.edge", empty);
		return Highlight.of(h -> h.fields(fields));
	}

	private List<SortOptions> sortOptions(SearchSort sort, Double latitude, Double longitude) {
		if (sort == SearchSort.DISTANCE && latitude != null && longitude != null) {
			// sort[0] = 거리(_geo_distance), sort[1] = 점수 — 응답 파싱 시 거리 추출의 기준이 된다
			return List.of(
				SortOptions.of(s -> s.geoDistance(GeoDistanceSort.of(g -> g
					.field(LOCATION)
					.location(GeoLocation.of(l -> l.latlon(ll -> ll.lat(latitude).lon(longitude))))
					.order(SortOrder.Asc)
					.unit(DistanceUnit.Meters)))),
				SortOptions.of(s -> s.score(ScoreSort.of(sc -> sc.order(SortOrder.Desc)))));
		}
		if (sort == SearchSort.POPULARITY) {
			return List.of(
				SortOptions.of(s -> s.field(FieldSort.of(f -> f
					.field(POPULARITY_SCORE)
					.order(SortOrder.Desc)
					.missing(FieldValue.of("_last"))))),
				SortOptions.of(s -> s.score(ScoreSort.of(sc -> sc.order(SortOrder.Desc)))));
		}
		return List.of();
	}
}
