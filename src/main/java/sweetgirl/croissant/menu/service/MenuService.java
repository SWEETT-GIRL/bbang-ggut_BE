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
import sweetgirl.croissant.menu.entity.MenuLike;
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
public class MenuService {

	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuLikeRepository menuLikeRepository;
	private final MemberRepository memberRepository;
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

	public MenuDetailResponse getMenuDetail(Long menuId, Long memberId) {
		Menu menu = menuRepository.findByIdWithDetails(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		List<ImageResponse> images = menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.map(img -> new ImageResponse(img.getId(), img.getAwsKey()))
			.toList();

		boolean isLiked = memberId != null && menuLikeRepository.existsByMemberIdAndMenuId(memberId, menuId);

		return MenuDetailResponse.of(menu, images, isLiked);
	}

	@Transactional
	public void likeMenu(Long menuId, Long memberId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if (menuLikeRepository.existsByMemberIdAndMenuId(memberId, menuId)) {
			throw new MenuException(MenuErrorCode.ALREADY_LIKED);
		}

		menuLikeRepository.save(MenuLike.builder()
			.member(memberRepository.getReferenceById(memberId))
			.menu(menu)
			.build());
		menuRepository.incrementLikeCount(menuId);
	}

	@Transactional
	public void cancelMenuLike(Long menuId, Long memberId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		MenuLike menuLike = menuLikeRepository.findByMemberIdAndMenuId(memberId, menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.NOT_LIKED));

		menuLikeRepository.delete(menuLike);
		menuRepository.decrementLikeCount(menuId);
	}

	private Map<Long, ImageResponse> buildMenuImageMap(List<Menu> menus) {
		List<Long> menuIds = menus.stream().map(Menu::getId).toList();
		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.toMap(
				MenuImageThumbnailProjection::getMenuId,
				p -> new ImageResponse(p.getImageId(), p.getAwsKey()),
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
