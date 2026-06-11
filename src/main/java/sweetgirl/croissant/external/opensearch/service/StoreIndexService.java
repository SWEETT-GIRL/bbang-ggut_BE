package sweetgirl.croissant.external.opensearch.service;

import java.io.IOException;
import java.util.List;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.external.opensearch.document.GeoPointDto;
import sweetgirl.croissant.external.opensearch.document.SearchRegionDto;
import sweetgirl.croissant.external.opensearch.document.StoreDocument;
import sweetgirl.croissant.external.opensearch.document.StoreMenuDto;
import sweetgirl.croissant.external.opensearch.exception.OpenSearchErrorCode;
import sweetgirl.croissant.external.opensearch.exception.OpenSearchException;
import sweetgirl.croissant.global.logging.LogEventType;
import sweetgirl.croissant.global.logging.repository.RequestLogRepository;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.NationwideHotStoreRanking;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.NationwideHotStoreRankingRepository;
import sweetgirl.croissant.store.repository.StoreSearchRegionRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Slf4j
@Service
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class StoreIndexService {

	private final OpenSearchClient openSearchClient;
	private final OpenSearchProperties openSearchProperties;
	private final MenuRepository menuRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final NationwideHotStoreRankingRepository nationwideRankingRepository;
	private final StoreSearchRegionRepository storeSearchRegionRepository;
	private final RequestLogRepository requestLogRepository;

	@Transactional(readOnly = true)
	public BulkResponse bulkIndexStores(List<Store> stores) {
		List<BulkOperation> operations = stores.stream()
			.map(store -> BulkOperation.of(op -> op
				.index(idx -> idx
					.index(openSearchProperties.getStoresIndex())
					.id(store.getId().toString())
					.document(buildDocument(store)))))
			.toList();
		try {
			return openSearchClient.bulk(r -> r.operations(operations));
		} catch (IOException e) {
			log.error("OpenSearch 벌크 색인 실패", e);
			throw new OpenSearchException(OpenSearchErrorCode.BULK_INDEX_FAILED, e);
		}
	}

	private StoreDocument buildDocument(Store store) {
		List<Menu> menus = menuRepository.findByStoreIdOrderByLikeCountDesc(store.getId());
		long subscribeCount = subscribedStoreRepository.countByStoreId(store.getId());
		List<NationwideHotStoreRanking> rankings = nationwideRankingRepository.findByStoreId(store.getId());

		List<String> breadTypes = menus.stream()
			.map(m -> m.getMenuCategory().getName().name())
			.distinct()
			.toList();

		List<StoreMenuDto> menuEntries = menus.stream()
			.map(m -> new StoreMenuDto(
				m.getId(),
				m.getName(),
				m.getMenuCategory().getName().name()))
			.toList();

		GeoPointDto geoPoint = new GeoPointDto(
			store.getLocation().getY(),
			store.getLocation().getX());

		double nationwideScore = rankings.stream()
			.mapToDouble(r -> Math.max(0, 11 - r.getRank()))
			.sum();

		long storeViewCount = requestLogRepository.countByStoreIdAndEventType(
			store.getId(), LogEventType.STORE_VIEW);

		// TODO: 가중치 계수(W1~W3) 확정 후 고도화
		double popularityScore = subscribeCount + nationwideScore + storeViewCount;

		List<SearchRegionDto> searchRegions = storeSearchRegionRepository
			.findSearchRegionsForIndexingByStoreId(store.getId())
			.stream()
			.map(r -> new SearchRegionDto(
				r.getRegionId(),
				r.getRegionName(),
				r.getRegionType().name(),
				r.getWeight().doubleValue()))
			.toList();

		List<String> searchRegionAliases = storeSearchRegionRepository
			.findSearchRegionAliasesForIndexingByStoreId(store.getId());

		return new StoreDocument(
			store.getId(),
			store.getName(),
			store.getAddress(),
			geoPoint,
			searchRegions,
			searchRegionAliases,
			breadTypes,
			menuEntries,
			popularityScore);
	}
}
