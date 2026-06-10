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

	public Map<String, Object> build(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, boolean sortByDistance) {
		List<Object> must = new ArrayList<>();
		List<Object> should = new ArrayList<>();
		boolean hasRegionTokens = !intent.regionTokens().isEmpty();
		boolean hasBreadTokens = !intent.breadTokens().isEmpty();

		if (hasRegionTokens) {
			Map<String, Object> regionMatch = boolShould(regionQueries(intent.regionTokens()));
			if (hasBreadTokens) {
				must.add(regionMatch);
			} else {
				should.add(regionMatch);
			}
		}

		if (hasBreadTokens) {
			must.add(boolShould(breadQueries(intent.breadTokens())));
		}

		should.add(rawQuery(intent.rawQuery()));

		Map<String, Object> bool = new LinkedHashMap<>();
		bool.put("must", must);
		bool.put("should", should);
		if (must.isEmpty()) {
			bool.put("minimum_should_match", 1);
		}

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("from", pageable.getOffset());
		request.put("size", pageable.getPageSize());
		request.put("query", functionScore(bool, latitude, longitude));
		if (sortByDistance && latitude != null && longitude != null) {
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
