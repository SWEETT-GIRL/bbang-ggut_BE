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

/**
 * 검색 엔진 결과(가게 ID·점수만 담긴 hit)를 받아,
 * 응답에 필요한 실제 데이터를 RDB에서 채워 넣어(hydration) 최종 응답 DTO로 조립한다.
 * 모든 조회는 ID 목록 기반 벌크 쿼리로 처리해 N+1을 피한다.
 */
@Component
@RequiredArgsConstructor
public class StoreSearchAssembler {

	private final StoreRepository storeRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuRepository menuRepository;

	public List<StoreSearchResponse> assemble(List<StoreSearchHit> hits) {
		if (hits.isEmpty()) {
			return List.of();
		}

		// 엔진의 랭킹 순서를 보존한 가게 ID 목록 (이후 응답 정렬의 기준이 된다)
		List<Long> orderedStoreIds = hits.stream()
			.map(StoreSearchHit::storeId)
			.distinct()
			.toList();
		// 가게 ID로 hit을 다시 찾기 위한 맵 (매칭된 메뉴 ID를 꺼낼 때 사용, 순서 유지를 위해 LinkedHashMap)
		Map<Long, StoreSearchHit> hitByStoreId = hits.stream()
			.collect(Collectors.toMap(
				StoreSearchHit::storeId,
				Function.identity(),
				(first, second) -> first,
				LinkedHashMap::new));
		// 가게 엔티티 벌크 조회 (findAllById는 순서를 보장하지 않으므로 맵으로 담아 둔다)
		Map<Long, Store> storeById = storeRepository.findAllById(orderedStoreIds).stream()
			.collect(Collectors.toMap(Store::getId, Function.identity()));
		// 가게/메뉴 썸네일과 대표 메뉴를 각각 벌크로 미리 조회
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(orderedStoreIds);
		// 응답에 실릴 메뉴를 먼저 고르고, 썸네일은 그 메뉴들 것만 읽는다 (가게의 메뉴 전부의 사진을 읽지 않는다)
		Map<Long, List<StoreMenuProjection>> topMenusByStore = selectMenusByStore(
			getTopMenusByStore(orderedStoreIds), hitByStoreId);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		// 엔진 순서대로 응답을 조립한다. 조립 중 삭제된 가게 등 RDB에 없는 가게는 걸러낸다.
		return orderedStoreIds.stream()
			.map(storeById::get)
			.filter(store -> store != null)
			.map(store -> StoreSearchResponse.ofDb(
				store,
				storeThumbnailMap.get(store.getId()),
				toMenuSummaries(topMenusByStore.getOrDefault(store.getId(), Collections.emptyList()),
					hitByStoreId.get(store.getId()).matchedMenuIds(),
					menuThumbnailMap),
				hitByStoreId.get(store.getId()).matchedFields()))
			.toList();
	}

	// 가게마다 응답에 실릴 메뉴만 남긴다 (가게 ID → 고른 메뉴 목록, 순서는 응답에 실리는 순서)
	private Map<Long, List<StoreMenuProjection>> selectMenusByStore(Map<Long, List<StoreMenuProjection>> menusByStore,
			Map<Long, StoreSearchHit> hitByStoreId) {
		Map<Long, List<StoreMenuProjection>> selected = new HashMap<>();
		menusByStore.forEach((storeId, menus) -> selected.put(storeId,
			orderMenus(menus, hitByStoreId.get(storeId).matchedMenuIds())));
		return selected;
	}

	/**
	 * 가게의 대표 메뉴를 정렬한다. 검색어에 실제 매칭된 메뉴(matchedMenuIds)를 앞쪽에 오도록 정렬하고,
	 * 상위 {@link sweetgirl.croissant.store.code.StoreConstants#TOP_MENU_LIMIT}개로 자른다.
	 */
	private List<StoreMenuProjection> orderMenus(List<StoreMenuProjection> menus, List<Long> matchedMenuIds) {
		// 매칭된 메뉴 ID → 매칭 순서. 작을수록 앞 순위 (중복 ID는 첫 등장 위치를 유지)
		Map<Long, Integer> matchedOrder = new HashMap<>();
		for (int i = 0; i < matchedMenuIds.size(); i++) {
			matchedOrder.putIfAbsent(matchedMenuIds.get(i), i);
		}

		// 매칭된 메뉴 우선 → 나머지(MAX_VALUE)는 뒤로 정렬한 뒤 상위 N개만 남긴다
		return menus.stream()
			.sorted(Comparator.comparingInt(menu -> matchedOrder.getOrDefault(menu.getMenuId(), Integer.MAX_VALUE)))
			.limit(TOP_MENU_LIMIT)
			.toList();
	}

	// 고른 메뉴를 응답 DTO 로 옮긴다 (썸네일을 붙이고, 검색어에 매칭된 메뉴인지 표시)
	private List<MenuSummaryDto> toMenuSummaries(List<StoreMenuProjection> menus, List<Long> matchedMenuIds,
			Map<Long, String> menuThumbnailMap) {
		return menus.stream()
			.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId()),
				matchedMenuIds.contains(menu.getMenuId())))
			.toList();
	}

	// 가게 ID → 리사이즈 썸네일 키 맵 (한 가게에 썸네일이 여럿이면 첫 번째만 사용 — 쿼리가 가게당 첫 장만 준다)
	private Map<Long, String> getStoreThumbnails(List<Long> storeIds) {
		return storeImageFileRepository.findFirstThumbnailsByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				p -> p.getResizedKey320() != null ? p.getResizedKey320() : p.getAwsKey(),
				(first, second) -> first));
	}

	// 가게 ID → 대표(추천) 메뉴 목록 맵
	private Map<Long, List<StoreMenuProjection>> getTopMenusByStore(List<Long> storeIds) {
		return menuRepository.findTopRecommendedMenusByStoreIds(storeIds).stream()
			.collect(Collectors.groupingBy(
				StoreMenuProjection::getStoreId,
				Collectors.collectingAndThen(
					Collectors.toCollection(ArrayList::new),
					List::copyOf)));
	}

	// 위에서 고른 대표 메뉴들의 ID를 평탄화해 메뉴 썸네일을 한 번에 조회 (메뉴 ID → resizedKey320, 메뉴당 첫 장만)
	private Map<Long, String> getMenuThumbnails(Map<Long, List<StoreMenuProjection>> menusByStore) {
		List<Long> menuIds = menusByStore.values().stream()
			.flatMap(List::stream)
			.map(StoreMenuProjection::getMenuId)
			.toList();

		if (menuIds.isEmpty()) {
			return Collections.emptyMap();
		}

		return menuImageFileRepository.findFirstThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.toMap(
				MenuImageThumbnailProjection::getMenuId,
				p -> p.getResizedKey320() != null ? p.getResizedKey320() : p.getAwsKey(),
				(first, second) -> first));
	}
}
