package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminMenuServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	AdminMenuService adminMenuService;

	@Test
	@DisplayName("createMenu: 가격을 Integer로 파싱해 메뉴를 저장한다")
	void createMenu_parsesPrice_savesMenu() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn("소금빵");
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(category));

		Menu saved = mock(Menu.class);
		given(saved.getId()).willReturn(10L);
		given(saved.getName()).willReturn("소금빵");
		given(saved.getPrice()).willReturn(3000);
		given(saved.getDescription()).willReturn(null);
		given(saved.getRecommendCount()).willReturn(0);
		given(saved.getDeletedAt()).willReturn(null);
		given(saved.getStore()).willReturn(store);
		given(saved.getMenuCategory()).willReturn(category);
		given(saved.getCreatedAt()).willReturn(null);
		given(menuRepository.save(any(Menu.class))).willReturn(saved);
		given(menuImageFileRepository.findImagesByMenuId(10L)).willReturn(Collections.emptyList());

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "소금빵", "3000원", null, "소금빵", "active");

		AdminMenuResponse response = adminMenuService.createMenu(1L, request);

		assertThat(response.price()).isEqualTo("3000원");
		assertThat(response.bakeryId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("createMenu: 유효하지 않은 카테고리면 예외를 던진다")
	void createMenu_invalidCategory_throwsException() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "테스트빵", "1000원", null, "존재하지않는카테고리", "active");

		assertThatThrownBy(() -> adminMenuService.createMenu(1L, request))
			.isInstanceOf(CustomException.class);
	}

	@Test
	@DisplayName("getStoreMenus: soft-delete 된 메뉴도 포함해 반환한다")
	void getStoreMenus_includesDeletedMenus() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.existsById(1L)).willReturn(true);

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn("소금빵");

		Menu activeMenu = mock(Menu.class);
		given(activeMenu.getId()).willReturn(1L);
		given(activeMenu.getName()).willReturn("소금빵");
		given(activeMenu.getPrice()).willReturn(3000);
		given(activeMenu.getDescription()).willReturn(null);
		given(activeMenu.getRecommendCount()).willReturn(0);
		given(activeMenu.getDeletedAt()).willReturn(null);
		given(activeMenu.getStore()).willReturn(store);
		given(activeMenu.getMenuCategory()).willReturn(category);
		given(activeMenu.getCreatedAt()).willReturn(null);

		Menu deletedMenu = mock(Menu.class);
		given(deletedMenu.getId()).willReturn(2L);
		given(deletedMenu.getName()).willReturn("삭제된빵");
		given(deletedMenu.getPrice()).willReturn(2000);
		given(deletedMenu.getDescription()).willReturn(null);
		given(deletedMenu.getRecommendCount()).willReturn(0);
		given(deletedMenu.getDeletedAt()).willReturn(LocalDateTime.now());
		given(deletedMenu.getStore()).willReturn(store);
		given(deletedMenu.getMenuCategory()).willReturn(category);
		given(deletedMenu.getCreatedAt()).willReturn(null);

		given(menuRepository.findByStoreIdIncludingDeleted(1L)).willReturn(List.of(activeMenu, deletedMenu));
		given(menuImageFileRepository.findThumbnailsByMenuIdIn(List.of(1L, 2L))).willReturn(Collections.emptyList());

		List<AdminMenuResponse> result = adminMenuService.getStoreMenus(1L);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).status()).isEqualTo("active");
		assertThat(result.get(1).status()).isEqualTo("inactive");
	}

	@Test
	@DisplayName("updateMenu: status가 inactive면 softDelete를 호출한다")
	void updateMenu_inactiveStatus_callsSoftDelete() {
		Menu menu = mock(Menu.class);
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

		adminMenuService.updateMenu(1L, new AdminMenuUpdateRequest(null, null, null, null, "inactive"));

		verify(menu).softDelete();
	}

	@Test
	@DisplayName("updateMenu: 없는 메뉴는 예외를 던진다")
	void updateMenu_notFound_throwsException() {
		given(menuRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminMenuService.updateMenu(999L,
			new AdminMenuUpdateRequest("이름", null, null, null, "active")))
			.isInstanceOf(CustomException.class);
	}
}
