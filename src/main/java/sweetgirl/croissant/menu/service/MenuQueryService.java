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
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.menu.dto.response.MenuDetailResponse;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.dto.projection.MenuReviewCountProjection;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuQueryService {

	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuLikeRepository menuLikeRepository;
	private final StoreRepository storeRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final ReviewRepository reviewRepository;

	public List<MenuCategoryResponse> getCategories() {
		return menuCategoryRepository.findAll().stream()
			.map(MenuCategoryResponse::from)
			.toList();
	}

	public List<StoreMenuResponse> getStoreMenus(Long storeId, Long memberId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}

		List<Menu> menus = menuRepository.findByStoreIdOrderByLikeCountDesc(storeId);
		if (menus.isEmpty()) {
			return Collections.emptyList();
		}
		Map<Long, ImageResponse> imageMap = buildMenuImageMap(menus);
		Map<Long, Long> reviewCountMap = buildReviewCountMap(menus, memberId);

		Set<Long> likedMenuIds = Collections.emptySet();
		if (memberId != null) {
			List<Long> menuIds = menus.stream().map(Menu::getId).toList();
			likedMenuIds = menuLikeRepository.findByMemberIdAndMenuIdIn(memberId, menuIds).stream()
				.map(ml -> ml.getMenu().getId())
				.collect(Collectors.toSet());
		}

		Set<Long> finalLikedMenuIds = likedMenuIds;
		return menus.stream()
			.map(menu -> StoreMenuResponse.of(
				menu,
				imageMap.get(menu.getId()),
				finalLikedMenuIds.contains(menu.getId()),
				reviewCountMap.getOrDefault(menu.getId(), 0L)))
			.toList();
	}

	public StoreMenuResponse getStoreMenu(Long menuId, Long memberId) {
		Menu menu = menuRepository.findByIdWithDetails(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));
		ImageResponse image = menuImageFileRepository.findThumbnailsByMenuIdIn(List.of(menuId)).stream()
			.findFirst()
			.map(projection -> new ImageResponse(
				projection.getImageId(),
				projection.getAwsKey(),
				projection.getResizedKey640()))
			.orElse(null);
		long reviewCount = reviewRepository.countVisibleActiveByMenuIds(List.of(menuId), memberId).stream()
			.findFirst()
			.map(MenuReviewCountProjection::getReviewCount)
			.orElse(0L);
		boolean isLiked = memberId != null && menuLikeRepository.existsByMemberIdAndMenuId(memberId, menuId);
		return StoreMenuResponse.of(menu, image, isLiked, reviewCount);
	}

	public MenuDetailResponse getMenuDetail(Long menuId, Long memberId) {
		Menu menu = menuRepository.findByIdWithDetails(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		List<ImageResponse> images = menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.map(img -> new ImageResponse(img.getId(), img.getAwsKey(), img.getResizedKey640()))
			.toList();

		boolean isLiked = memberId != null && menuLikeRepository.existsByMemberIdAndMenuId(memberId, menuId);

		return MenuDetailResponse.of(menu, images, isLiked);
	}

	private Map<Long, ImageResponse> buildMenuImageMap(List<Menu> menus) {
		List<Long> menuIds = menus.stream().map(Menu::getId).toList();
		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.toMap(
				MenuImageThumbnailProjection::getMenuId,
				p -> new ImageResponse(p.getImageId(), p.getAwsKey(), p.getResizedKey640()),
				(first, second) -> first));
	}

	private Map<Long, Long> buildReviewCountMap(List<Menu> menus, Long memberId) {
		List<Long> menuIds = menus.stream().map(Menu::getId).toList();
		return reviewRepository.countVisibleActiveByMenuIds(menuIds, memberId).stream()
			.collect(Collectors.toMap(
				MenuReviewCountProjection::getMenuId,
				MenuReviewCountProjection::getReviewCount));
	}
}
