package sweetgirl.croissant.external.opensearch.client;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.external.opensearch.dto.MatchedField;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.external.opensearch.query.StoreSearchQueryBuilder;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchSort;

@Component
public class OpenSearchStoreClient {

	private static final String SEARCH_PATH_FORMAT = "/%s/_search";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final RestClient openSearchRestClient;
	private final OpenSearchProperties openSearchProperties;
	private final StoreSearchQueryBuilder storeSearchQueryBuilder;

	public OpenSearchStoreClient(@Qualifier("openSearchRestClient") RestClient openSearchRestClient,
			OpenSearchProperties openSearchProperties,
			StoreSearchQueryBuilder storeSearchQueryBuilder) {
		this.openSearchRestClient = openSearchRestClient;
		this.openSearchProperties = openSearchProperties;
		this.storeSearchQueryBuilder = storeSearchQueryBuilder;
	}

	public OpenSearchStoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		Map<String, Object> request = storeSearchQueryBuilder.build(intent, pageable, latitude, longitude,
			sort);
		JsonNode response;
		try {
			String body = openSearchRestClient.post()
				.uri(SEARCH_PATH_FORMAT.formatted(openSearchProperties.getStoresIndex()))
				.body(request)
				.retrieve()
				.body(String.class);
			if (body == null) {
				return OpenSearchStoreSearchResult.empty();
			}
			response = MAPPER.readTree(body);
		} catch (Exception e) {
			return OpenSearchStoreSearchResult.empty();
		}
		return parseResponse(response, pageable);
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
			List<Long> matchedMenuIds = parseMatchedMenuIds(hitNode);
			hits.add(new OpenSearchStoreHit(
				storeId,
				nullableDouble(hitNode.path("_score")),
				parseDistance(hitNode),
				matchedMenuIds,
				parseMatchedFields(hitNode, matchedMenuIds)));
		}
		return new OpenSearchStoreSearchResult(hits, hits.size() == pageable.getPageSize());
	}

	private List<MatchedField> parseMatchedFields(JsonNode hitNode, List<Long> matchedMenuIds) {
		Set<MatchedField> fields = EnumSet.noneOf(MatchedField.class);
		JsonNode highlight = hitNode.path("highlight");
		if (highlight.isObject()) {
			Iterator<String> fieldNames = highlight.fieldNames();
			while (fieldNames.hasNext()) {
				String field = fieldNames.next();
				if (field.startsWith("storeName")) {
					fields.add(MatchedField.STORE_NAME);
				} else if (field.startsWith("searchRegionAliases")) {
					fields.add(MatchedField.REGION);
				} else if (field.startsWith("breadTypes")) {
					fields.add(MatchedField.BREAD_TYPE);
				}
			}
		}
		if (!matchedMenuIds.isEmpty()) {
			fields.add(MatchedField.MENU);
		}
		return List.copyOf(fields);
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
		try {
			return MAPPER.convertValue(node, Long.class);
		} catch (Exception e) {
			return null;
		}
	}

	private Double nullableDouble(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		try {
			return MAPPER.convertValue(node, Double.class);
		} catch (Exception e) {
			return null;
		}
	}
}
