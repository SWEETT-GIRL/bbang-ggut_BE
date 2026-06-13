package sweetgirl.croissant.external.opensearch.client;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.JsonpSerializable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.InnerHitsResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import jakarta.json.stream.JsonGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.opensearch.document.StoreDocument;
import sweetgirl.croissant.external.opensearch.dto.MatchedField;
import sweetgirl.croissant.external.opensearch.dto.MenuInnerHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.external.opensearch.query.StoreSearchQueryBuilder;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchSort;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchStoreClient {

	private final ObjectProvider<OpenSearchClient> openSearchClientProvider;
	private final StoreSearchQueryBuilder storeSearchQueryBuilder;

	public OpenSearchStoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		OpenSearchClient client = openSearchClientProvider.getIfAvailable();
		if (client == null) {
			return OpenSearchStoreSearchResult.empty();
		}

		JsonpMapper mapper = client._transport().jsonpMapper();
		SearchRequest request = storeSearchQueryBuilder.build(intent, pageable, latitude, longitude, sort);
		if (log.isDebugEnabled()) {
			log.debug("OpenSearch 검색 요청 JSON: {}", toJson(request, mapper));
		}
		try {
			SearchResponse<StoreDocument> response = client.search(request, StoreDocument.class);
			if (log.isDebugEnabled()) {
				log.debug("OpenSearch 검색 응답 JSON: {}", toJson(response, mapper));
			}
			return parseResponse(response, pageable, sort, mapper);
		} catch (IOException | RuntimeException e) {
			log.warn("OpenSearch 가게 검색 실패 — 빈 결과로 대체합니다.", e);
			return OpenSearchStoreSearchResult.empty();
		}
	}

	private OpenSearchStoreSearchResult parseResponse(SearchResponse<StoreDocument> response, Pageable pageable,
			SearchSort sort, JsonpMapper mapper) {
		List<OpenSearchStoreHit> hits = new ArrayList<>();
		for (Hit<StoreDocument> hit : response.hits().hits()) {
			StoreDocument source = hit.source();
			if (source == null || source.storeId() == null) {
				continue;
			}
			List<Long> matchedMenuIds = parseMatchedMenuIds(hit, mapper);
			List<MatchedField> matchedFields = parseMatchedFields(hit, matchedMenuIds);
			if (log.isDebugEnabled()) {
				log.debug("OpenSearch hit 파싱 결과 storeId={}, score={}, matchedFields={}, "
					+ "matchedMenuIds={}, highlightKeys={}, innerHitNames={}",
					source.storeId(), hit.score(), matchedFields, matchedMenuIds,
					hit.highlight().keySet(), hit.innerHits().keySet());
			}
			hits.add(new OpenSearchStoreHit(
				source.storeId(),
				hit.score(),
				parseDistance(hit, sort),
				matchedMenuIds,
				matchedFields));
		}
		return new OpenSearchStoreSearchResult(hits, hits.size() == pageable.getPageSize());
	}

	private List<MatchedField> parseMatchedFields(Hit<StoreDocument> hit, List<Long> matchedMenuIds) {
		Set<MatchedField> fields = EnumSet.noneOf(MatchedField.class);
		for (String field : hit.highlight().keySet()) {
			if (field.startsWith("storeName")) {
				fields.add(MatchedField.STORE_NAME);
			} else if (field.startsWith("searchRegionAliases")) {
				fields.add(MatchedField.REGION);
			} else if (field.startsWith("breadTypes")) {
				fields.add(MatchedField.BREAD_TYPE);
			}
		}
		if (!matchedMenuIds.isEmpty()) {
			fields.add(MatchedField.MENU);
		}
		return List.copyOf(fields);
	}

	private List<Long> parseMatchedMenuIds(Hit<StoreDocument> hit, JsonpMapper mapper) {
		List<Long> menuIds = new ArrayList<>();
		for (InnerHitsResult innerHits : hit.innerHits().values()) {
			for (Hit<JsonData> innerHit : innerHits.hits().hits()) {
				JsonData innerSource = innerHit.source();
				if (innerSource == null) {
					continue;
				}
				MenuInnerHit menu = innerSource.to(MenuInnerHit.class, mapper);
				if (menu.id() != null && !menuIds.contains(menu.id())) {
					menuIds.add(menu.id());
				}
			}
		}
		return menuIds;
	}

	private Double parseDistance(Hit<StoreDocument> hit, SearchSort sort) {
		if (sort != SearchSort.DISTANCE) {
			return null;
		}
		List<FieldValue> sortValues = hit.sort();
		if (sortValues.isEmpty()) {
			return null;
		}
		try {
			return sortValues.get(0).doubleValue();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private String toJson(JsonpSerializable value, JsonpMapper mapper) {
		StringWriter writer = new StringWriter();
		try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
			value.serialize(generator, mapper);
		}
		return writer.toString();
	}
}
