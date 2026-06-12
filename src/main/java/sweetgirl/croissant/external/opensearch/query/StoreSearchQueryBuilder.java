package sweetgirl.croissant.external.opensearch.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.store.service.search.SearchIntent;

@Component
public class StoreSearchQueryBuilder {

	private static final int INNER_HITS_SIZE = 5;

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
	public Map<String, Object> build(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, boolean sortByDistance) {
		List<Object> must = new ArrayList<>();
		List<Object> should = new ArrayList<>();
		boolean hasRegionTokens = !intent.regionTokens().isEmpty();
		boolean hasBreadTokens = !intent.breadTokens().isEmpty();

		if (hasRegionTokens) {
			Map<String, Object> regionMatch = boolShould(regionQueries(intent.regionTokens()));
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

		// 원문 전체를 가게명/메뉴명에 매칭 — must와 무관하게 항상 should에 포함
		should.add(rawQuery(intent.rawQuery()));

		Map<String, Object> bool = new LinkedHashMap<>();
		bool.put("must", must);
		bool.put("should", should);
		if (must.isEmpty()) {
			// 케이스 2, 4: must가 비어있으면 should 중 최소 1개는 매칭돼야 결과에 포함
			bool.put("minimum_should_match", 1);
		}

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("from", pageable.getOffset());
		request.put("size", pageable.getPageSize());
		request.put("query", functionScore(bool, latitude, longitude));
		if (sortByDistance && latitude != null && longitude != null) {
			// 케이스 3, 4: 지역어 없을 때 거리순 정렬로 사용자 주변 가게 우선
			request.put("sort", distanceSort(latitude, longitude));
		}
		return request;
	}

	private List<Object> regionQueries(List<String> regionTokens) {
		List<Object> queries = new ArrayList<>();
		for (String regionToken : regionTokens) {
			queries.add(regionQuery(regionToken));
		}
		return queries;
	}

	private List<Object> breadQueries(List<String> breadTokens) {
		List<Object> queries = new ArrayList<>();
		for (int i = 0; i < breadTokens.size(); i++) {
			queries.add(breadQuery(breadTokens.get(i), "matchedMenus" + i));
		}
		return queries;
	}

	private Map<String, Object> functionScore(Map<String, Object> bool, Double latitude, Double longitude) {
		List<Object> functions = new ArrayList<>();
		functions.add(Map.of(
			"field_value_factor", Map.of(
				"field", "popularityScore",
				"factor", 0.2,
				"modifier", "sqrt",
				"missing", 0)));
		if (latitude != null && longitude != null) {
			functions.add(Map.of(
				"gauss", Map.of(
					"location", Map.of(
						"origin", Map.of("lat", latitude, "lon", longitude),
						"scale", "3km",
						"offset", "500m",
						"decay", 0.5))));
		}
		return Map.of(
			"function_score", Map.of(
				"query", Map.of("bool", bool),
				"functions", functions,
				"boost_mode", "sum",
				"score_mode", "sum"));
	}

	private Map<String, Object> rawQuery(String query) {
		return boolShould(List.of(
			multiMatch(query, List.of(
				"storeName^3",
				"storeName.edge^2",
				"storeName.ngram")),
			rawMenuQuery(query, "rawMatchedMenus")));
	}

	private Map<String, Object> regionQuery(String query) {
		return boolShould(List.of(
			Map.of("nested", Map.of(
				"path", "searchRegions",
				"query", multiMatch(query, List.of(
					"searchRegions.name^4",
					"searchRegions.name.nori^3",
					"searchRegions.name.edge^2",
					"searchRegions.name.ngram^2")))),
			multiMatch(query, List.of(
				"searchRegionAliases^4",
				"searchRegionAliases.nori^3",
				"searchRegionAliases.edge^2",
				"searchRegionAliases.ngram^2"))));
	}

	private Map<String, Object> breadQuery(String query, String innerHitsName) {
		return boolShould(List.of(
			multiMatch(query, List.of(
				"breadTypes^4",
				"breadTypes.nori^3",
				"breadTypes.edge^2",
				"breadTypes.ngram^2")),
			menuNestedQuery(query, innerHitsName)));
	}

	private Map<String, Object> rawMenuQuery(String query, String innerHitsName) {
		return menuNested(query, innerHitsName, List.of(
			"menus.name^3",
			"menus.name.edge^2",
			"menus.name.ngram"));
	}

	private Map<String, Object> menuNestedQuery(String query, String innerHitsName) {
		return menuNested(query, innerHitsName, List.of(
			"menus.name^4",
			"menus.name.edge^3",
			"menus.name.ngram^2",
			"menus.menuCategory^2",
			"menus.menuCategory.nori",
			"menus.menuCategory.edge",
			"menus.menuCategory.ngram"));
	}

	private Map<String, Object> menuNested(String query, String innerHitsName, List<String> fields) {
		return Map.of("nested", Map.of(
			"path", "menus",
			"query", multiMatch(query, fields),
			"inner_hits", Map.of(
				"name", innerHitsName,
				"size", INNER_HITS_SIZE,
				"_source", List.of("menus.id", "menus.name"))));
	}

	private Map<String, Object> multiMatch(String query, List<String> fields) {
		return Map.of("multi_match", Map.of(
			"query", query,
			"fields", fields,
			"type", "best_fields",
			"operator", "and"));
	}

	private Map<String, Object> boolShould(List<Object> should) {
		return Map.of("bool", Map.of(
			"should", should,
			"minimum_should_match", 1));
	}

	private List<Object> distanceSort(Double latitude, Double longitude) {
		return List.of(
			Map.of("_geo_distance", Map.of(
				"location", Map.of("lat", latitude, "lon", longitude),
				"order", "asc",
				"unit", "m")),
			Map.of("_score", Map.of("order", "desc")));
	}
}
