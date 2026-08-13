package sweetgirl.croissant.external.opensearch.service;

import static sweetgirl.croissant.external.opensearch.document.StoreDocumentConstants.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import sweetgirl.croissant.store.dto.projection.StoreIdCountProjection;
import sweetgirl.croissant.store.dto.projection.StoreNameAliasProjection;
import sweetgirl.croissant.store.dto.projection.StoreSearchRegionAliasProjection;
import sweetgirl.croissant.store.dto.projection.StoreSearchRegionIndexProjection;
import sweetgirl.croissant.store.entity.NationwideHotStoreRanking;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.NationwideHotStoreRankingRepository;
import sweetgirl.croissant.store.repository.StoreNameAliasRepository;
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
	private final StoreNameAliasRepository storeNameAliasRepository;
	private final RequestLogRepository requestLogRepository;

	// 신규 가게는 문서 전체를 만들어 넣는다 — 생성 색인이 없으면 다음 전체 재색인까지
	// 검색에서 안 잡혀, 인기 목록(DB 직조회)에는 뜨는 가게가 검색은 0건이 된다.
	@Transactional(readOnly = true)
	public void indexStore(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		bulkIndexStores(List.of(store));
	}

	@Transactional(readOnly = true)
	public BulkResponse bulkIndexStores(List<Store> stores) {
		List<Long> storeIds = stores.stream().map(Store::getId).toList();

		Map<Long, List<Menu>> menusByStoreId = menuRepository
			.findByStoreIdInOrderByLikeCountDesc(storeIds)
			.stream()
			.collect(Collectors.groupingBy(m -> m.getStore().getId()));

		Map<Long, Long> subscribeCountsByStoreId = subscribedStoreRepository
			.countByStoreIdIn(storeIds)
			.stream()
			.collect(Collectors.toMap(StoreIdCountProjection::getStoreId, StoreIdCountProjection::getCount));

		Map<Long, List<NationwideHotStoreRanking>> rankingsByStoreId = nationwideRankingRepository
			.findByStoreIdIn(storeIds)
			.stream()
			.collect(Collectors.groupingBy(r -> r.getStore().getId()));

		Map<Long, List<StoreSearchRegionIndexProjection>> searchRegionsByStoreId = storeSearchRegionRepository
			.findSearchRegionsForIndexingByStoreIds(storeIds)
			.stream()
			.collect(Collectors.groupingBy(StoreSearchRegionIndexProjection::getStoreId));

		Map<Long, List<String>> aliasesByStoreId = storeSearchRegionRepository
			.findSearchRegionAliasesForIndexingByStoreIds(storeIds)
			.stream()
			.collect(Collectors.groupingBy(
				StoreSearchRegionAliasProjection::getStoreId,
				Collectors.mapping(StoreSearchRegionAliasProjection::getAlias, Collectors.toList())));

		Map<Long, List<String>> nameAliasesByStoreId = storeNameAliasRepository
			.findAliasesForIndexingByStoreIds(storeIds)
			.stream()
			.collect(Collectors.groupingBy(
				StoreNameAliasProjection::getStoreId,
				Collectors.mapping(StoreNameAliasProjection::getAlias, Collectors.toList())));

		Map<Long, Long> viewCountsByStoreId = requestLogRepository
			.countByStoreIdInAndEventType(storeIds, LogEventType.STORE_VIEW)
			.stream()
			.collect(Collectors.toMap(StoreIdCountProjection::getStoreId, StoreIdCountProjection::getCount));

		List<BulkOperation> operations = stores.stream()
			.map(store -> BulkOperation.of(op -> op
				.index(idx -> idx
					.index(openSearchProperties.getStoresIndex())
					.id(store.getId().toString())
					.document(buildDocument(store, menusByStoreId, subscribeCountsByStoreId,
						rankingsByStoreId, searchRegionsByStoreId, aliasesByStoreId,
						nameAliasesByStoreId, viewCountsByStoreId)))))
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
				Map<String, Object> scoreDoc = Map.of(POPULARITY_SCORE, score);
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
		partial.put(ADDRESS, store.getAddress());
		partial.put(LOCATION, new GeoPointDto(store.getLocation().getY(), store.getLocation().getX()));
		partial.put(SEARCH_REGIONS, buildSearchRegions(storeId));
		partial.put(SEARCH_REGION_ALIASES,
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
			.map(m -> new StoreMenuDto(m.getId(), m.getName(), categoryNameOf(m)))
			.toList();
		List<String> breadTypes = menus.stream()
			.map(this::categoryNameOf)
			.filter(category -> category != null)
			.distinct()
			.toList();

		Map<String, Object> partial = new HashMap<>();
		partial.put(MENUS, menuEntries);
		partial.put(BREAD_TYPES, breadTypes);

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

	@Transactional(readOnly = true)
	public void updateStoreName(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		try {
			// 이름이 바뀌는 순간이 곧 구명 alias 가 생기는 순간 — 이름과 alias 를 함께 갱신한다
			openSearchClient.update(u -> u
				.index(openSearchProperties.getStoresIndex())
				.id(storeId.toString())
				.doc(Map.of(
					STORE_NAME, store.getName(),
					STORE_NAME_ALIASES, storeNameAliasRepository.findAliasesForIndexingByStoreId(storeId))),
				StoreDocument.class);
		} catch (IOException e) {
			log.error("OpenSearch 가게 이름 업데이트 실패 storeId={}", storeId, e);
			throw new OpenSearchException(OpenSearchErrorCode.BULK_INDEX_FAILED, e);
		}
	}

	public void deleteStoreDocument(Long storeId) {
		try {
			openSearchClient.delete(d -> d
				.index(openSearchProperties.getStoresIndex())
				.id(storeId.toString()));
		} catch (IOException e) {
			log.error("OpenSearch 가게 document 삭제 실패 storeId={}", storeId, e);
			throw new OpenSearchException(OpenSearchErrorCode.BULK_INDEX_FAILED, e);
		}
	}

	private StoreDocument buildDocument(
			Store store,
			Map<Long, List<Menu>> menusByStoreId,
			Map<Long, Long> subscribeCountsByStoreId,
			Map<Long, List<NationwideHotStoreRanking>> rankingsByStoreId,
			Map<Long, List<StoreSearchRegionIndexProjection>> searchRegionsByStoreId,
			Map<Long, List<String>> aliasesByStoreId,
			Map<Long, List<String>> nameAliasesByStoreId,
			Map<Long, Long> viewCountsByStoreId) {

		Long storeId = store.getId();
		List<Menu> menus = menusByStoreId.getOrDefault(storeId, List.of());

		List<String> breadTypes = menus.stream()
			.map(this::categoryNameOf)
			.filter(category -> category != null)
			.distinct()
			.toList();

		List<StoreMenuDto> menuEntries = menus.stream()
			.map(m -> new StoreMenuDto(m.getId(), m.getName(), categoryNameOf(m)))
			.toList();

		GeoPointDto geoPoint = new GeoPointDto(
			store.getLocation().getY(),
			store.getLocation().getX());

		List<SearchRegionDto> searchRegions = searchRegionsByStoreId
			.getOrDefault(storeId, List.of())
			.stream()
			.map(r -> new SearchRegionDto(r.getRegionId(), r.getRegionName(),
				r.getRegionType().name(), r.getWeight().doubleValue()))
			.toList();

		return new StoreDocument(
			storeId,
			store.getName(),
			nameAliasesByStoreId.getOrDefault(storeId, List.of()),
			store.getAddress(),
			geoPoint,
			searchRegions,
			aliasesByStoreId.getOrDefault(storeId, List.of()),
			breadTypes,
			menuEntries,
			calculatePopularityScoreFromMaps(store, subscribeCountsByStoreId, rankingsByStoreId,
				viewCountsByStoreId));
	}

	private double calculatePopularityScore(Store store) {
		long subscribeCount = subscribedStoreRepository.countByStoreId(store.getId());
		List<NationwideHotStoreRanking> rankings = nationwideRankingRepository.findByStoreId(store.getId());
		double nationwideScore = rankings.stream()
			.mapToDouble(r -> Math.max(0, 51 - r.getRank()))
			.sum();
		long storeViewCount = requestLogRepository.countByStoreIdAndEventType(store.getId(), LogEventType.STORE_VIEW);
		// TODO: 가중치 계수(W1~W3) 확정 후 고도화
		return subscribeCount + nationwideScore + storeViewCount;
	}

	private String categoryNameOf(Menu menu) {
		return menu.getMenuCategory() != null ? menu.getMenuCategory().getName().name() : null;
	}

	private double calculatePopularityScoreFromMaps(
			Store store,
			Map<Long, Long> subscribeCountsByStoreId,
			Map<Long, List<NationwideHotStoreRanking>> rankingsByStoreId,
			Map<Long, Long> viewCountsByStoreId) {

		long subscribeCount = subscribeCountsByStoreId.getOrDefault(store.getId(), 0L);
		double nationwideScore = rankingsByStoreId
			.getOrDefault(store.getId(), List.of())
			.stream()
			.mapToDouble(r -> Math.max(0, 51 - r.getRank()))
			.sum();
		long storeViewCount = viewCountsByStoreId.getOrDefault(store.getId(), 0L);
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
