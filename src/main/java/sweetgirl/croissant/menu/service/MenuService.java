package sweetgirl.croissant.menu.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.dto.ImageResponse;
import sweetgirl.croissant.menu.dto.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreRepository storeRepository;

	public List<StoreMenuResponse> getStoreMenus(Long storeId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}

		List<Menu> menus = menuRepository.findByStoreIdOrderByRecommendCountDesc(storeId);
		if (menus.isEmpty()) {
			return Collections.emptyList();
		}
		Map<Long, ImageResponse> imageMap = buildMenuImageMap(menus);
		return menus.stream()
			.map(menu -> StoreMenuResponse.of(menu, imageMap.get(menu.getId())))
			.toList();
	}

	private Map<Long, ImageResponse> buildMenuImageMap(List<Menu> menus) {
		List<Long> menuIds = menus.stream().map(Menu::getId).toList();
		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.toMap(
				MenuImageThumbnailProjection::getMenuId,
				p -> new ImageResponse(p.getImageId(), p.getAwsKey()),
				(first, second) -> first));
	}
}
