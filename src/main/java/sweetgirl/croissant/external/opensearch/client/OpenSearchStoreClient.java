package sweetgirl.croissant.external.opensearch.client;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.JsonpSerializable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
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
import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.external.opensearch.document.SearchRegionDto;
import sweetgirl.croissant.external.opensearch.document.StoreDocument;
import sweetgirl.croissant.external.opensearch.document.StoreMenuDto;
import sweetgirl.croissant.external.opensearch.dto.MatchedField;
import sweetgirl.croissant.external.opensearch.dto.MenuInnerHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchAutocompleteSuggestion;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.external.opensearch.query.StoreSearchQueryBuilder;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchSort;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchStoreClient {

	private static final int AUTOCOMPLETE_FETCH_MULTIPLIER = 3;
	private static final int AUTOCOMPLETE_MAX_FETCH_SIZE = 50;
	private static final Pattern AUTOCOMPLETE_NORMALIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9가-힣]");

	private final ObjectProvider<OpenSearchClient> openSearchClientProvider;
	private final StoreSearchQueryBuilder storeSearchQueryBuilder;
	private final OpenSearchProperties openSearchProperties;

	public OpenSearchStoreSearchResult searchStores(SearchIntent intent, Pageable pageable,
			Double latitude, Double longitude, SearchSort sort) {
		OpenSearchClient client = openSearchClientProvider.getIfAvailable();
		if (client == null) {
			return OpenSearchStoreSearchResult.empty();
		}

		JsonpMapper mapper = client._transport().jsonpMapper();
		// explain은 전용 프로퍼티로만 켠다(디버그 전용). 운영 기본은 false, 로컬에서만 true.
		// 로그 레벨(관측)과 분리해 DEBUG 로깅을 켜도 explain 스코어링 비용이 붙지 않도록 한다.
		boolean explain = openSearchProperties.isExplainEnabled();
		SearchRequest request = storeSearchQueryBuilder.build(intent, pageable, latitude, longitude, sort, explain);
		if (log.isDebugEnabled()) {
			log.debug("OpenSearch 검색 요청 JSON: {}", toJson(request, mapper));
		}
		try {
			SearchResponse<StoreDocument> response = client.search(request, StoreDocument.class);
			if (log.isDebugEnabled()) {
				log.debug("OpenSearch 검색 응답 JSON: {}", toJson(response, mapper));
			}
			return parseResponse(response, pageable, sort, mapper);
		} catch (OpenSearchException e) {
			ErrorCause error = e.error();
			log.warn("OpenSearch 가게 검색 실패 — 빈 결과로 대체합니다. status={}, type={}, reason={}, causes={}",
				e.status(),
				error != null ? error.type() : null,
				error != null ? error.reason() : null,
				describeCauses(error), e);
			return OpenSearchStoreSearchResult.empty();
		} catch (IOException | RuntimeException e) {
			log.warn("OpenSearch 가게 검색 실패 — 빈 결과로 대체합니다.", e);
			return OpenSearchStoreSearchResult.empty();
		}
	}

	public List<OpenSearchAutocompleteSuggestion> autocompleteStores(String query, int size) {
		OpenSearchClient client = openSearchClientProvider.getIfAvailable();
		if (client == null) {
			return List.of();
		}

		String normalizedQuery = normalizeAutocompleteText(query);
		if (normalizedQuery.isBlank()) {
			return List.of();
		}

		JsonpMapper mapper = client._transport().jsonpMapper();
		SearchRequest request = storeSearchQueryBuilder.buildAutocomplete(query,
			Math.min(size * AUTOCOMPLETE_FETCH_MULTIPLIER, AUTOCOMPLETE_MAX_FETCH_SIZE));
		if (log.isDebugEnabled()) {
			log.debug("OpenSearch 자동완성 요청 JSON: {}", toJson(request, mapper));
		}
		try {
			SearchResponse<StoreDocument> response = client.search(request, StoreDocument.class);
			if (log.isDebugEnabled()) {
				log.debug("OpenSearch 자동완성 응답 JSON: {}", toJson(response, mapper));
			}
			return parseAutocompleteResponse(response, normalizedQuery);
		} catch (OpenSearchException e) {
			ErrorCause error = e.error();
			log.warn("OpenSearch 자동완성 실패 — 빈 결과로 대체합니다. status={}, type={}, reason={}, causes={}",
				e.status(),
				error != null ? error.type() : null,
				error != null ? error.reason() : null,
				describeCauses(error), e);
			return List.of();
		} catch (IOException | RuntimeException e) {
			log.warn("OpenSearch 자동완성 실패 — 빈 결과로 대체합니다.", e);
			return List.of();
		}
	}

	// search_phase_execution_exception(all shards failed) 등 상위 메시지에 가려진 샤드 단위 실제 원인을 펼쳐 로깅한다.
	private String describeCauses(ErrorCause error) {
		if (error == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (ErrorCause root : error.rootCause()) {
			sb.append("[rootCause type=").append(root.type())
				.append(", reason=").append(root.reason()).append("] ");
		}
		for (ErrorCause caused = error.causedBy(); caused != null; caused = caused.causedBy()) {
			sb.append("[causedBy type=").append(caused.type())
				.append(", reason=").append(caused.reason()).append("] ");
		}
		return sb.toString();
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
				if (hit.explanation() != null) {
					log.debug("OpenSearch 점수 산출 내역 storeId={}, score={}: {}",
						source.storeId(), hit.score(), toJson(hit.explanation(), mapper));
				}
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

	private List<OpenSearchAutocompleteSuggestion> parseAutocompleteResponse(
			SearchResponse<StoreDocument> response, String normalizedQuery) {
		List<OpenSearchAutocompleteSuggestion> suggestions = new ArrayList<>();
		for (Hit<StoreDocument> hit : response.hits().hits()) {
			StoreDocument source = hit.source();
			if (source == null) {
				continue;
			}
			addAutocompleteSuggestion(suggestions, source.storeName(), AutocompleteSuggestionType.STORE,
				source.storeId(), normalizedQuery);
			for (String breadType : nullSafeList(source.breadTypes())) {
				addAutocompleteSuggestion(suggestions, breadType, AutocompleteSuggestionType.BREAD_TYPE,
					null, normalizedQuery);
			}
			for (StoreMenuDto menu : nullSafeList(source.menus())) {
				if (menu != null) {
					addAutocompleteSuggestion(suggestions, menu.name(), AutocompleteSuggestionType.MENU,
						null, normalizedQuery);
				}
			}
			for (String alias : nullSafeList(source.searchRegionAliases())) {
				addAutocompleteSuggestion(suggestions, alias, AutocompleteSuggestionType.REGION,
					null, normalizedQuery);
			}
			for (SearchRegionDto region : nullSafeList(source.searchRegions())) {
				if (region != null) {
					addAutocompleteSuggestion(suggestions, region.name(), AutocompleteSuggestionType.REGION,
						null, normalizedQuery);
				}
			}
		}
		return suggestions;
	}

	private void addAutocompleteSuggestion(List<OpenSearchAutocompleteSuggestion> suggestions,
			String keyword, AutocompleteSuggestionType type, Long storeId, String normalizedQuery) {
		if (keyword == null || !normalizeAutocompleteText(keyword).contains(normalizedQuery)) {
			return;
		}
		suggestions.add(new OpenSearchAutocompleteSuggestion(keyword, type, storeId));
	}

	private <T> List<T> nullSafeList(List<T> values) {
		return values != null ? values : List.of();
	}

	private String normalizeAutocompleteText(String text) {
		if (text == null) {
			return "";
		}
		return AUTOCOMPLETE_NORMALIZE_PATTERN.matcher(text)
			.replaceAll("")
			.toLowerCase(Locale.ROOT);
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
