package sweetgirl.croissant.external.opensearch.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.store.service.search.SearchIntent;

@Component
public class OpenSearchStoreClient {

	private static final String SEARCH_PATH_FORMAT = "/%s/_search";

	private final RestClient openSearchRestClient;
	private final OpenSearchProperties openSearchProperties;
	private final ObjectMapper objectMapper;

	public OpenSearchStoreClient(@Qualifier("openSearchRestClient") RestClient openSearchRestClient,
			OpenSearchProperties openSearchProperties, ObjectMapper objectMapper) {
		this.openSearchRestClient = openSearchRestClient;
		this.openSearchProperties = openSearchProperties;
		this.objectMapper = objectMapper;
	}

	public OpenSearchStoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude) {
		Map<String, Object> request = buildSearchRequest(intent, pageable, latitude, longitude);
		JsonNode response;
		try {
			response = openSearchRestClient.post()
				.uri(SEARCH_PATH_FORMAT.formatted(openSearchProperties.getStoresIndex()))
				.body(request)
				.retrieve()
				.body(JsonNode.class);
		} catch (RestClientException e) {
			return OpenSearchStoreSearchResult.empty();
		}
		return parseResponse(response, pageable);
	}

	private Map<String, Object> buildSearchRequest(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude) {
		List<Object> must = new ArrayList<>();
		List<Object> should = new ArrayList<>();
		boolean hasRegionTokens = !intent.regionTokens().isEmpty();
		boolean hasBreadTokens = !intent.breadTokens().isEmpty();

		if (hasRegionTokens) {
			List<Object> regionQueries = new ArrayList<>();
			for (String regionToken : intent.regionTokens()) {
				regionQueries.add(regionQuery(regionToken));
			}
			Map<String, Object> regionMatch = Map.of("bool", Map.of(
				"should", regionQueries,
				"minimum_should_match", 1));
			if (hasBreadTokens) {
				must.add(regionMatch);
			} else {
				should.add(regionMatch);
			}
		}

		if (hasBreadTokens) {
			List<Object> breadQueries = new ArrayList<>();
			for (int i = 0; i < intent.breadTokens().size(); i++) {
				breadQueries.add(breadQuery(intent.breadTokens().get(i), "matchedMenus" + i));
			}
			must.add(Map.of("bool", Map.of(
				"should", breadQueries,
				"minimum_should_match", 1)));
		}

		should.add(rawQuery(intent.rawQuery()));

		Map<String, Object> bool = new LinkedHashMap<>();
		bool.put("must", must);
		if (!should.isEmpty()) {
			bool.put("should", should);
			if (must.isEmpty()) {
				bool.put("minimum_should_match", 1);
			}
		}

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

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("from", pageable.getOffset());
		request.put("size", pageable.getPageSize());
		request.put("query", Map.of(
			"function_score", Map.of(
				"query", Map.of("bool", bool),
				"functions", functions,
				"boost_mode", "sum",
				"score_mode", "sum")));
		if (latitude != null && longitude != null) {
			request.put("sort", List.of(
				Map.of("_score", Map.of("order", "desc")),
				Map.of("_geo_distance", Map.of(
					"location", Map.of("lat", latitude, "lon", longitude),
					"order", "asc",
					"unit", "m"))));
		}
		return request;
	}

	private Map<String, Object> multiMatch(String query, List<String> fields) {
		return Map.of("multi_match", Map.of(
			"query", query,
			"fields", fields,
			"type", "best_fields",
			"operator", "and"));
	}

	private Map<String, Object> rawQuery(String query) {
		return Map.of("bool", Map.of(
			"should", List.of(
				multiMatch(query, List.of(
					"storeName^3",
					"storeName.edge^2",
					"storeName.ngram")),
				rawMenuQuery(query, "rawMatchedMenus")),
			"minimum_should_match", 1));
	}

	private Map<String, Object> regionQuery(String query) {
		return Map.of("bool", Map.of(
			"should", List.of(
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
					"searchRegionAliases.ngram^2"))),
			"minimum_should_match", 1));
	}

	private Map<String, Object> breadQuery(String query, String innerHitsName) {
		return Map.of("bool", Map.of(
			"should", List.of(
				multiMatch(query, List.of(
					"breadTypes^4",
					"breadTypes.nori^3",
					"breadTypes.edge^2",
					"breadTypes.ngram^2")),
				menuNestedQuery(query, innerHitsName)),
			"minimum_should_match", 1));
	}

	private Map<String, Object> rawMenuQuery(String query, String innerHitsName) {
		return Map.of("nested", Map.of(
			"path", "menus",
			"query", multiMatch(query, List.of(
				"menus.name^3",
				"menus.name.edge^2",
				"menus.name.ngram")),
			"inner_hits", Map.of(
				"name", innerHitsName,
				"size", 5,
				"_source", List.of("menus.id", "menus.name"))));
	}

	private Map<String, Object> menuNestedQuery(String query, String innerHitsName) {
		return Map.of("nested", Map.of(
			"path", "menus",
			"query", multiMatch(query, List.of(
				"menus.name^4",
				"menus.name.edge^3",
				"menus.name.ngram^2",
				"menus.menuCategory^2",
				"menus.menuCategory.nori",
				"menus.menuCategory.edge",
				"menus.menuCategory.ngram")),
			"inner_hits", Map.of(
				"name", innerHitsName,
				"size", 5,
				"_source", List.of("menus.id", "menus.name"))));
	}

	private OpenSearchStoreSearchResult parseResponse(JsonNode response, Pageable pageable) {
		JsonNode hitsNode = response == null ? null : response.path("hits").path("hits");
		if (hitsNode == null || !hitsNode.isArray()) {
			return OpenSearchStoreSearchResult.empty();
		}

		List<OpenSearchStoreHit> hits = new ArrayList<>();
		for (JsonNode hitNode : hitsNode) {
			Long storeId = nullableLong(hitNode.path("_source").path("storeId"));
			if (storeId == null) {
				continue;
			}
			hits.add(new OpenSearchStoreHit(
				storeId,
				nullableDouble(hitNode.path("_score")),
				parseDistance(hitNode),
				parseMatchedMenuIds(hitNode)));
		}
		return new OpenSearchStoreSearchResult(hits, hits.size() == pageable.getPageSize());
	}

	private List<Long> parseMatchedMenuIds(JsonNode hitNode) {
		JsonNode innerHitsNode = hitNode.path("inner_hits");
		if (!innerHitsNode.isObject()) {
			return List.of();
		}
		List<Long> menuIds = new ArrayList<>();
		Iterator<String> names = innerHitsNode.fieldNames();
		while (names.hasNext()) {
			JsonNode innerHits = innerHitsNode.path(names.next()).path("hits").path("hits");
			if (!innerHits.isArray()) {
				continue;
			}
			for (JsonNode innerHit : innerHits) {
				Long menuId = nullableLong(innerHit.path("_source").path("id"));
				if (menuId == null) {
					menuId = nullableLong(innerHit.path("_source").path("menus").path("id"));
				}
				if (menuId != null && !menuIds.contains(menuId)) {
					menuIds.add(menuId);
				}
			}
		}
		return menuIds;
	}

	private Double parseDistance(JsonNode hitNode) {
		JsonNode sort = hitNode.path("sort");
		if (sort.isArray() && sort.size() > 1) {
			return nullableDouble(sort.get(1));
		}
		return null;
	}

	private Long nullableLong(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		return objectMapper.convertValue(node, Long.class);
	}

	private Double nullableDouble(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		return objectMapper.convertValue(node, Double.class);
	}
}
