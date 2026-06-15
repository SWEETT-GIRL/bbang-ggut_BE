package sweetgirl.croissant.external.opensearch.query;

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
import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchSort;

@Component
@RequiredArgsConstructor
public class StoreSearchQueryBuilder {

	private static final int INNER_HITS_SIZE = 5;
	private static final String MINIMUM_SHOULD_MATCH = "1";

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
	 */
	public SearchRequest build(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		List<Query> must = new ArrayList<>();
		List<Query> should = new ArrayList<>();
		boolean hasRegionTokens = !intent.regionTokens().isEmpty();
		boolean hasBreadTokens = !intent.breadTokens().isEmpty();

		if (hasRegionTokens) {
			Query regionMatch = boolShould(regionQueries(intent.regionTokens()));
			if (hasBreadTokens) {
				// 케이스 1: 지역 + 빵 → 지역도 must (둘 다 필수 조건)
				must.add(regionMatch);
			} else {
				// 케이스 2: 지역만 → should (지역 매칭될수록 점수 높음)
				should.add(regionMatch);
			}
		}

		if (hasBreadTokens) {
			// 케이스 1, 3: 빵 키워드는 항상 must (에그타르트 없는 가게는 제외)
			must.add(boolShould(breadQueries(intent.breadTokens())));
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
			.query(functionScore(bool, latitude, longitude))
			.highlight(highlight());

		List<SortOptions> sortOptions = sortOptions(sort, latitude, longitude);
		if (!sortOptions.isEmpty()) {
			request.sort(sortOptions);
		}
		return request.build();
	}

	public SearchRequest buildAutocomplete(String query, int size) {
		Query autocompleteQuery = boolShould(List.of(
			multiMatch(query, List.of(
				"storeName.edge^5",
				"storeName.ngram^2",
				"breadTypes.edge^4",
				"breadTypes.ngram^2",
				"searchRegionAliases.edge^2",
				"searchRegionAliases.ngram")),
			Query.of(q -> q.nested(n -> n
				.path("menus")
				.query(multiMatch(query, List.of(
					"menus.name.edge^4",
					"menus.name.ngram^2",
					"menus.menuCategory.edge^2",
					"menus.menuCategory.ngram"))))),
			Query.of(q -> q.nested(n -> n
				.path("searchRegions")
				.query(multiMatch(query, List.of(
					"searchRegions.name.edge^2",
					"searchRegions.name.ngram")))))));

		return new SearchRequest.Builder()
			.index(openSearchProperties.getStoresIndex())
			.size(size)
			.query(functionScore(autocompleteQuery, null, null))
			.build();
	}

	private List<Query> regionQueries(List<String> regionTokens) {
		List<Query> queries = new ArrayList<>();
		for (String regionToken : regionTokens) {
			queries.add(regionQuery(regionToken));
		}
		return queries;
	}

	private List<Query> breadQueries(List<String> breadTokens) {
		List<Query> queries = new ArrayList<>();
		for (int i = 0; i < breadTokens.size(); i++) {
			queries.add(breadQuery(breadTokens.get(i), "matchedMenus" + i));
		}
		return queries;
	}

	private Query functionScore(Query bool, Double latitude, Double longitude) {
		List<FunctionScore> functions = new ArrayList<>();
		// baseline 1.0 — 곱셈(Multiply) 시 인기도/거리 점수가 0이어도 텍스트 점수가 사라지지 않도록 보장
		functions.add(FunctionScore.of(fn -> fn.weight(1.0f)));
		// 인기도는 텍스트 점수를 "덮어쓰지" 않고 완만하게 증폭만 — Log1p로 조회수 폭주 가게의 과도한 보정 방지
		functions.add(FunctionScore.of(fn -> fn.fieldValueFactor(f -> f
			.field("popularityScore")
			.factor(0.3f)
			.modifier(FieldValueFactorModifier.Log1p)
			.missing(0.0))));
		if (latitude != null && longitude != null) {
			Map<String, Object> origin = Map.of("lat", latitude, "lon", longitude);
			functions.add(FunctionScore.of(fn -> fn.gauss(d -> d
				.field("location")
				.placement(p -> p
					.origin(JsonData.of(origin))
					.scale(JsonData.of("3km"))
					.offset(JsonData.of("500m"))
					.decay(0.5)))));
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
		return boolShould(List.of(
			Query.of(q -> q.term(t -> t
				.field("storeName.keyword")
				.value(v -> v.stringValue(query))
				.boost(20.0f))),
			Query.of(q -> q.term(t -> t
				.field("storeName.edge")
				.value(v -> v.stringValue(normalizeForExactMatch(query)))
				.boost(80.0f))),
			Query.of(q -> q.term(t -> t
				.field("storeName.ngram")
				.value(v -> v.stringValue(normalizeForExactMatch(query)))
				.boost(60.0f))),
			multiMatch(query, List.of(
				"storeName^3",
				"storeName.edge^2",
				"storeName.ngram")),
			menuNested(query, "rawMatchedMenus", List.of(
				"menus.name^3",
				"menus.name.edge^2",
				"menus.name.ngram"))));
	}

	private Query regionQuery(String query) {
		return boolShould(List.of(
			Query.of(q -> q.nested(n -> n
				.path("searchRegions")
				.query(multiMatch(query, List.of(
					"searchRegions.name^4",
					"searchRegions.name.nori^3",
					"searchRegions.name.edge^2",
					"searchRegions.name.ngram^2"))))),
			multiMatch(query, List.of(
				"searchRegionAliases^4",
				"searchRegionAliases.nori^3",
				"searchRegionAliases.edge^2",
				"searchRegionAliases.ngram^2"))));
	}

	private Query breadQuery(String query, String innerHitsName) {
		return boolShould(List.of(
			multiMatch(query, List.of(
				"breadTypes^4",
				"breadTypes.nori^3",
				"breadTypes.edge^2",
				"breadTypes.ngram^2")),
			menuNested(query, innerHitsName, List.of(
				"menus.name^4",
				"menus.name.edge^3",
				"menus.name.ngram^2",
				"menus.menuCategory^2",
				"menus.menuCategory.nori",
				"menus.menuCategory.edge",
				"menus.menuCategory.ngram"))));
	}

	private Query menuNested(String query, String innerHitsName, List<String> fields) {
		return Query.of(q -> q.nested(n -> n
			.path("menus")
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
					.field("location")
					.location(GeoLocation.of(l -> l.latlon(ll -> ll.lat(latitude).lon(longitude))))
					.order(SortOrder.Asc)
					.unit(DistanceUnit.Meters)))),
				SortOptions.of(s -> s.score(ScoreSort.of(sc -> sc.order(SortOrder.Desc)))));
		}
		if (sort == SearchSort.POPULARITY) {
			return List.of(
				SortOptions.of(s -> s.field(FieldSort.of(f -> f
					.field("popularityScore")
					.order(SortOrder.Desc)
					.missing(FieldValue.of("_last"))))),
				SortOptions.of(s -> s.score(ScoreSort.of(sc -> sc.order(SortOrder.Desc)))));
		}
		return List.of();
	}
}
