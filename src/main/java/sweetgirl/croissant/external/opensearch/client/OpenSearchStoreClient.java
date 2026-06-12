package sweetgirl.croissant.external.opensearch.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.external.opensearch.query.StoreSearchQueryBuilder;
import sweetgirl.croissant.store.service.search.SearchIntent;

@Component
public class OpenSearchStoreClient {

	private static final String SEARCH_PATH_FORMAT = "/%s/_search";

	private final ObjectProvider<OpenSearchClient> openSearchClientProvider;
	private final OpenSearchProperties openSearchProperties;
	private final ObjectMapper objectMapper;
	private final StoreSearchQueryBuilder storeSearchQueryBuilder;

	public OpenSearchStoreClient(ObjectProvider<OpenSearchClient> openSearchClientProvider,
			OpenSearchProperties openSearchProperties, ObjectMapper objectMapper,
			StoreSearchQueryBuilder storeSearchQueryBuilder) {
		this.openSearchClientProvider = openSearchClientProvider;
		this.openSearchProperties = openSearchProperties;
		this.objectMapper = objectMapper;
		this.storeSearchQueryBuilder = storeSearchQueryBuilder;
	}

	public OpenSearchStoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude) {
		OpenSearchClient openSearchClient = openSearchClientProvider.getIfAvailable();
		if (openSearchClient == null) {
			return OpenSearchStoreSearchResult.empty();
		}

		Map<String, Object> request = storeSearchQueryBuilder.build(intent, pageable, latitude, longitude, false);
		JsonNode response;
		try {
			try (Response genericResponse = openSearchClient.generic()
				.execute(Requests.builder()
					.method("POST")
					.endpoint(SEARCH_PATH_FORMAT.formatted(openSearchProperties.getStoresIndex()))
					.json(objectMapper.writeValueAsString(request))
					.build())) {
				if (genericResponse.getStatus() >= 400 || genericResponse.getBody().isEmpty()) {
					return OpenSearchStoreSearchResult.empty();
				}
				response = objectMapper.readTree(genericResponse.getBody().get().body());
			}
		} catch (IOException | OpenSearchException e) {
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
