package sweetgirl.croissant.admin.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMenuQueryService {

	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreRepository storeRepository;

	public List<AdminMenuResponse> getStoreMenus(Long storeId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		List<Menu> menus = menuRepository.findByStoreIdIncludingDeleted(storeId);
		if (menus.isEmpty()) {
			return Collections.emptyList();
		}

		List<Long> menuIds = menus.stream().map(Menu::getId).toList();
		Map<Long, List<String>> photoMap = menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.groupingBy(
				MenuImageThumbnailProjection::getMenuId,
				Collectors.mapping(MenuImageThumbnailProjection::getAwsKey, Collectors.toList())));

		return menus.stream()
			.map(menu -> AdminMenuResponse.of(
				menu,
				photoMap.getOrDefault(menu.getId(), Collections.emptyList())))
			.toList();
	}

	public Page<AdminMenuResponse> getAllMenus(int page, int size) {
		Page<Menu> menus = menuRepository.findAllMenusWithDetails(PageRequest.of(page, size));
		List<Long> menuIds = menus.getContent().stream().map(Menu::getId).toList();
		Map<Long, List<String>> photoMap = buildPhotoMap(menuIds);
		return menus
			.map(menu -> AdminMenuResponse.of(menu, photoMap.getOrDefault(menu.getId(), Collections.emptyList())));
	}

	public AdminMenuResponse getMenuDetail(Long menuId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));
		List<String> photos = menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.map(ImageFile::getAwsKey)
			.toList();
		return AdminMenuResponse.of(menu, photos);
	}

	private Map<Long, List<String>> buildPhotoMap(List<Long> menuIds) {
		if (menuIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.groupingBy(
				MenuImageThumbnailProjection::getMenuId,
				Collectors.mapping(MenuImageThumbnailProjection::getAwsKey, Collectors.toList())));
	}
}
