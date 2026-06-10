package sweetgirl.croissant.store.service.search;

import static sweetgirl.croissant.store.code.StoreConstants.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class StoreSearchAssembler {

	private final StoreRepository storeRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuRepository menuRepository;

	public List<StoreSearchResponse> assemble(List<OpenSearchStoreHit> hits) {
		if (hits.isEmpty()) {
			return List.of();
		}

		List<Long> orderedStoreIds = hits.stream()
			.map(OpenSearchStoreHit::storeId)
			.distinct()
			.toList();
		Map<Long, OpenSearchStoreHit> hitByStoreId = hits.stream()
			.collect(Collectors.toMap(
				OpenSearchStoreHit::storeId,
				Function.identity(),
				(first, second) -> first,
				LinkedHashMap::new));
		Map<Long, Store> storeById = storeRepository.findAllById(orderedStoreIds).stream()
			.collect(Collectors.toMap(Store::getId, Function.identity()));
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(orderedStoreIds);
		Map<Long, List<StoreMenuProjection>> topMenusByStore = getTopMenusByStore(orderedStoreIds);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		return orderedStoreIds.stream()
			.map(storeById::get)
			.filter(store -> store != null)
			.map(store -> StoreSearchResponse.ofDb(
				store,
				storeThumbnailMap.get(store.getId()),
				orderMenus(topMenusByStore.getOrDefault(store.getId(), Collections.emptyList()),
					hitByStoreId.get(store.getId()).matchedMenuIds(),
					menuThumbnailMap)))
			.toList();
	}

	private List<MenuSummaryDto> orderMenus(List<StoreMenuProjection> menus, List<Long> matchedMenuIds,
			Map<Long, String> menuThumbnailMap) {
		Map<Long, Integer> matchedOrder = new HashMap<>();
		for (int i = 0; i < matchedMenuIds.size(); i++) {
			matchedOrder.putIfAbsent(matchedMenuIds.get(i), i);
		}

		return menus.stream()
			.sorted(Comparator.comparingInt(menu -> matchedOrder.getOrDefault(menu.getMenuId(), Integer.MAX_VALUE)))
			.limit(TOP_MENU_LIMIT)
			.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId())))
			.toList();
	}

	private Map<Long, String> getStoreThumbnails(List<Long> storeIds) {
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				StoreImageThumbnailProjection::getAwsKey,
				(first, second) -> first));
	}

	private Map<Long, List<StoreMenuProjection>> getTopMenusByStore(List<Long> storeIds) {
		return menuRepository.findTopRecommendedMenusByStoreIds(storeIds).stream()
			.collect(Collectors.groupingBy(
				StoreMenuProjection::getStoreId,
				Collectors.collectingAndThen(
					Collectors.toCollection(ArrayList::new),
					List::copyOf)));
	}

	private Map<Long, String> getMenuThumbnails(Map<Long, List<StoreMenuProjection>> menusByStore) {
		List<Long> menuIds = menusByStore.values().stream()
			.flatMap(List::stream)
			.map(StoreMenuProjection::getMenuId)
			.toList();

		if (menuIds.isEmpty()) {
			return Collections.emptyMap();
		}

		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.toMap(
				MenuImageThumbnailProjection::getMenuId,
				MenuImageThumbnailProjection::getAwsKey,
				(first, second) -> first));
	}
}
