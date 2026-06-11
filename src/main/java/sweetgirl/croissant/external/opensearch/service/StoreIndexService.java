package sweetgirl.croissant.external.opensearch.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.NationwideHotStoreRanking;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.NationwideHotStoreRankingRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.StoreSearchRegionRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Slf4j
@Service
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class StoreIndexService {

	private final OpenSearchClient openSearchClient;
	private final OpenSearchProperties openSearchProperties;
	private final StoreRepository storeRepository;
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

	@Transactional(readOnly = true)
	public void bulkUpdateScores(List<Store> stores) {
		List<BulkOperation> operations = stores.stream()
			.map(store -> {
				double score = calculatePopularityScore(store);
				Map<String, Object> scoreDoc = Map.of("popularityScore", score);
				return BulkOperation.of(op -> op
					.update(u -> u
						.index(openSearchProperties.getStoresIndex())
						.id(store.getId().toString())
						.document(scoreDoc)));
			})
			.toList();
		try {
			openSearchClient.bulk(r -> r.operations(operations));
		} catch (IOException e) {
			log.error("OpenSearch popularityScore 벌크 업데이트 실패", e);
			throw new OpenSearchException(OpenSearchErrorCode.BULK_INDEX_FAILED, e);
		}
	}

	@Transactional(readOnly = true)
	public void updateStoreAddress(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		Map<String, Object> partial = new HashMap<>();
		partial.put("address", store.getAddress());
		partial.put("location", new GeoPointDto(store.getLocation().getY(), store.getLocation().getX()));
		partial.put("searchRegions", buildSearchRegions(storeId));
		partial.put("searchRegionAliases",
			storeSearchRegionRepository.findSearchRegionAliasesForIndexingByStoreId(storeId));

		try {
			openSearchClient.update(u -> u
				.index(openSearchProperties.getStoresIndex())
				.id(storeId.toString())
				.doc(partial), StoreDocument.class);
		} catch (IOException e) {
			log.error("OpenSearch 주소 업데이트 실패 storeId={}", storeId, e);
			throw new OpenSearchException(OpenSearchErrorCode.BULK_INDEX_FAILED, e);
		}
	}

	@Transactional(readOnly = true)
	public void updateStoreMenus(Long storeId) {
		List<Menu> menus = menuRepository.findByStoreIdOrderByLikeCountDesc(storeId);

		List<StoreMenuDto> menuEntries = menus.stream()
			.map(m -> new StoreMenuDto(m.getId(), m.getName(), m.getMenuCategory().getName().name()))
			.toList();
		List<String> breadTypes = menus.stream()
			.map(m -> m.getMenuCategory().getName().name())
			.distinct()
			.toList();

		Map<String, Object> partial = new HashMap<>();
		partial.put("menus", menuEntries);
		partial.put("breadTypes", breadTypes);

		try {
			openSearchClient.update(u -> u
				.index(openSearchProperties.getStoresIndex())
				.id(storeId.toString())
				.doc(partial), StoreDocument.class);
		} catch (IOException e) {
			log.error("OpenSearch 메뉴 업데이트 실패 storeId={}", storeId, e);
			throw new OpenSearchException(OpenSearchErrorCode.BULK_INDEX_FAILED, e);
		}
	}

	private StoreDocument buildDocument(Store store) {
		List<Menu> menus = menuRepository.findByStoreIdOrderByLikeCountDesc(store.getId());

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

		return new StoreDocument(
			store.getId(),
			store.getName(),
			store.getAddress(),
			geoPoint,
			buildSearchRegions(store.getId()),
			storeSearchRegionRepository.findSearchRegionAliasesForIndexingByStoreId(store.getId()),
			breadTypes,
			menuEntries,
			calculatePopularityScore(store));
	}

	private double calculatePopularityScore(Store store) {
		long subscribeCount = subscribedStoreRepository.countByStoreId(store.getId());
		List<NationwideHotStoreRanking> rankings = nationwideRankingRepository.findByStoreId(store.getId());
		double nationwideScore = rankings.stream()
			.mapToDouble(r -> Math.max(0, 11 - r.getRank()))
			.sum();
		long storeViewCount = requestLogRepository.countByStoreIdAndEventType(store.getId(), LogEventType.STORE_VIEW);
		// TODO: 가중치 계수(W1~W3) 확정 후 고도화
		return subscribeCount + nationwideScore + storeViewCount;
	}

	private List<SearchRegionDto> buildSearchRegions(Long storeId) {
		return storeSearchRegionRepository.findSearchRegionsForIndexingByStoreId(storeId)
			.stream()
			.map(r -> new SearchRegionDto(
				r.getRegionId(),
				r.getRegionName(),
				r.getRegionType().name(),
				r.getWeight().doubleValue()))
			.toList();
	}
}
