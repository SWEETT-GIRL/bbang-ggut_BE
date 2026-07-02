package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.dto.projection.MenuReviewCountProjection;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class MenuQueryServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	MenuLikeRepository menuLikeRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;
	@Mock
	ReviewRepository reviewRepository;

	@InjectMocks
	MenuQueryService menuQueryService;

	@Test
	@DisplayName("getStoreMenus: 메뉴별 visible active 리뷰 수를 한 번에 집계하고 없는 메뉴는 0으로 반환한다")
	void getStoreMenus_returnsReviewCountWithoutNPlusOne() {
		Menu menuWithReviews = menu(1L, "소금빵", 3500, 12);
		Menu menuWithoutReviews = menu(2L, "식빵", 5000, 3);
		ReflectionTestUtils.setField(menuWithReviews, "description", "짭짤한 버터 풍미");
		List<Long> menuIds = List.of(1L, 2L);

		given(storeRepository.existsById(10L)).willReturn(true);
		given(menuRepository.findByStoreIdOrderByLikeCountDesc(10L))
			.willReturn(List.of(menuWithReviews, menuWithoutReviews));
		given(menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds)).willReturn(List.of());
		given(menuLikeRepository.findByMemberIdAndMenuIdIn(99L, menuIds)).willReturn(List.of());
		given(reviewRepository.countVisibleActiveByMenuIds(menuIds, 99L))
			.willReturn(List.of(reviewCount(1L, 8L)));

		List<StoreMenuResponse> result = menuQueryService.getStoreMenus(10L, 99L);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).menuId()).isEqualTo(1L);
		assertThat(result.get(0).description()).isEqualTo("짭짤한 버터 풍미");
		assertThat(result.get(0).reviewCount()).isEqualTo(8L);
		assertThat(result.get(1).menuId()).isEqualTo(2L);
		assertThat(result.get(1).description()).isNull();
		assertThat(result.get(1).reviewCount()).isZero();
		verify(reviewRepository).countVisibleActiveByMenuIds(eq(menuIds), eq(99L));
		verify(reviewRepository, never()).countVisibleActiveByMenuId(1L, 99L);
		verify(reviewRepository, never()).countVisibleActiveByMenuId(2L, 99L);
	}

	private Menu menu(Long id, String name, Integer price, int likeCount) {
		Store store = Store.builder()
			.name("단팥당")
			.address("서울 마포구")
			.build();
		Menu menu = Menu.builder()
			.store(store)
			.menuCategory(MenuCategory.builder().name(MenuKind.빵).build())
			.name(name)
			.price(price)
			.build();
		ReflectionTestUtils.setField(menu, "id", id);
		ReflectionTestUtils.setField(menu, "likeCount", likeCount);
		return menu;
	}

	private MenuReviewCountProjection reviewCount(Long menuId, Long reviewCount) {
		return new MenuReviewCountProjection() {
			@Override
			public Long getMenuId() {
				return menuId;
			}

			@Override
			public Long getReviewCount() {
				return reviewCount;
			}
		};
	}
}
