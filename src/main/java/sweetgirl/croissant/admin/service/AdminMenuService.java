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
import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuReorderRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMenuService {

	private final MenuRepository menuRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreRepository storeRepository;

	@Transactional
	public AdminMenuResponse createMenu(Long storeId, AdminMenuCreateRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		MenuCategory menuCategory = resolveCategory(request.category());
		Integer price = parsePrice(request.price());

		Menu menu = Menu.builder()
			.store(store)
			.menuCategory(menuCategory)
			.name(request.name())
			.price(price)
			.description(request.desc())
			.build();
		menu = menuRepository.save(menu);

		List<String> photoUrls = menuImageFileRepository.findImagesByMenuId(menu.getId()).stream()
			.map(img -> img.getAwsKey())
			.toList();
		return AdminMenuResponse.of(menu, photoUrls);
	}

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

	@Transactional
	public void updateMenu(Long menuId, AdminMenuUpdateRequest request) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if ("inactive".equals(request.status())) {
			menu.softDelete();
			return;
		}

		MenuCategory newCategory = request.category() != null ? resolveCategory(request.category()) : null;
		Integer newPrice = request.price() != null ? parsePrice(request.price()) : null;
		menu.update(request.name(), newPrice, request.desc(), newCategory);
	}

	@Transactional
	public void deleteMenu(Long menuId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));
		menu.softDelete();
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
			.map(img -> img.getAwsKey())
			.toList();
		return AdminMenuResponse.of(menu, photos);
	}

	@Transactional
	public void reorderMenus(Long storeId, AdminMenuReorderRequest request) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		List<Long> menuIds = request.orders().stream()
			.map(AdminMenuReorderRequest.MenuOrder::menuId)
			.toList();
		List<Menu> menus = menuRepository.findAllById(menuIds);
		Map<Long, Integer> orderMap = request.orders().stream()
			.collect(Collectors.toMap(
				AdminMenuReorderRequest.MenuOrder::menuId,
				AdminMenuReorderRequest.MenuOrder::order));
		menus.forEach(menu -> menu.updateOrder(orderMap.get(menu.getId())));
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

	private MenuCategory resolveCategory(String categoryName) {
		if (categoryName == null || categoryName.isBlank()) {
			return null;
		}
		try {
			MenuKind kind = MenuKind.valueOf(categoryName);
			return menuCategoryRepository.findByName(kind)
				.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND));
		} catch (IllegalArgumentException e) {
			throw new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND);
		}
	}

	private Integer parsePrice(String priceStr) {
		if (priceStr == null || priceStr.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(priceStr.replace("원", "").trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
