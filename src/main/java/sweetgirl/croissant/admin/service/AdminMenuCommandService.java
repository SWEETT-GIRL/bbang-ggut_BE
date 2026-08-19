package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuReorderRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminMenuCommandService {

	private final MenuRepository menuRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreRepository storeRepository;

	public AdminMenuResponse createMenu(Long storeId, AdminMenuCreateRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		// Idempotency + enrich — 같은 store 의 활성 메뉴 중 정규화 이름이 일치하면 새로 만들지 않고
		// 기존 메뉴를 반환한다(스크래퍼 재시도/재크롤 시 중복 생성 방지). 이때 비어 있는 가격·설명은
		// 요청 값으로 보강한다 — LocalData 한글 메뉴의 빈 필드가 네이버 크롤로 채워지도록.
		// soft-delete 된 메뉴는 어드민이 의도적으로 지운 것으로 보고 무시.
		String normalized = MenuNameNormalizer.normalize(request.name());
		Menu existing = menuRepository.findActiveByStoreId(storeId).stream()
			.filter(m -> MenuNameNormalizer.normalize(m.getName()).equals(normalized))
			.findFirst()
			.orElse(null);
		if (existing != null) {
			existing.enrichIfMissing(parsePrice(request.price()), request.desc());
			List<String> existingPhotoUrls = menuImageFileRepository.findImagesByMenuId(existing.getId()).stream()
				.map(img -> img.getAwsKey())
				.toList();
			return AdminMenuResponse.of(existing, existingPhotoUrls);
		}

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

	public void updateMenu(Long menuId, AdminMenuUpdateRequest request) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if ("inactive".equals(request.status())) {
			menu.softDelete();
		} else {
			MenuCategory newCategory = request.category() != null ? resolveCategory(request.category()) : null;
			Integer newPrice = parsePrice(request.price());
			menu.update(request.name(), newPrice, request.desc(), newCategory);
		}
	}

	public void deleteMenu(Long menuId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));
		menu.softDelete();
	}

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
			int val = Integer.parseInt(priceStr.replace("원", "").trim());
			return val == 0 ? null : val;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
