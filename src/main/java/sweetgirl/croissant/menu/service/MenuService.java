package sweetgirl.croissant.menu.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.dto.ImageResponse;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.menu.dto.response.MenuDetailResponse;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.RecommendMenu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.repository.RecommendMenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final RecommendMenuRepository recommendMenuRepository;
	private final MemberRepository memberRepository;
	private final StoreRepository storeRepository;
	private final MenuCategoryRepository menuCategoryRepository;

	public List<MenuCategoryResponse> getCategories() {
		return menuCategoryRepository.findAll().stream()
			.map(MenuCategoryResponse::from)
			.toList();
	}

	public List<StoreMenuResponse> getStoreMenus(Long storeId, Long memberId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}

		List<Menu> menus = menuRepository.findByStoreIdOrderByRecommendCountDesc(storeId);
		if (menus.isEmpty()) {
			return Collections.emptyList();
		}
		Map<Long, ImageResponse> imageMap = buildMenuImageMap(menus);

		Set<Long> recommendedMenuIds = Collections.emptySet();
		if (memberId != null) {
			List<Long> menuIds = menus.stream().map(Menu::getId).toList();
			recommendedMenuIds = recommendMenuRepository.findByMemberIdAndMenuIdIn(memberId, menuIds).stream()
				.map(rm -> rm.getMenu().getId())
				.collect(Collectors.toSet());
		}

		Set<Long> finalRecommendedMenuIds = recommendedMenuIds;
		return menus.stream()
			.map(menu -> StoreMenuResponse.of(
				menu,
				imageMap.get(menu.getId()),
				finalRecommendedMenuIds.contains(menu.getId())))
			.toList();
	}

	public MenuDetailResponse getMenuDetail(Long menuId) {
		Menu menu = menuRepository.findByIdWithDetails(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		List<ImageResponse> images = menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.map(img -> new ImageResponse(img.getId(), img.getAwsKey()))
			.toList();

		return MenuDetailResponse.of(menu, images);
	}

	@Transactional
	public void recommendMenu(Long menuId, Long memberId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if (recommendMenuRepository.existsByMemberIdAndMenuId(memberId, menuId)) {
			throw new MenuException(MenuErrorCode.ALREADY_RECOMMENDED);
		}

		recommendMenuRepository.save(RecommendMenu.builder()
			.member(memberRepository.getReferenceById(memberId))
			.menu(menu)
			.build());
		menuRepository.incrementRecommendCount(menuId);
	}

	@Transactional
	public void cancelMenuRecommend(Long menuId, Long memberId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		RecommendMenu recommendMenu = recommendMenuRepository.findByMemberIdAndMenuId(memberId, menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.NOT_RECOMMENDED));

		recommendMenuRepository.delete(recommendMenu);
		menuRepository.decrementRecommendCount(menuId);
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
