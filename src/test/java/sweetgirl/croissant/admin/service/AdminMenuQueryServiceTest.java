package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminMenuQueryServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	AdminMenuQueryService adminMenuQueryService;

	@Test
	@DisplayName("getStoreMenus: soft-delete 된 메뉴도 포함해 반환한다")
	void getStoreMenus_includesDeletedMenus() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.existsById(1L)).willReturn(true);

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn(MenuKind.소금빵);

		Menu activeMenu = mock(Menu.class);
		given(activeMenu.getId()).willReturn(1L);
		given(activeMenu.getName()).willReturn("소금빵");
		given(activeMenu.getPrice()).willReturn(3000);
		given(activeMenu.getDescription()).willReturn(null);
		given(activeMenu.getLikeCount()).willReturn(0);
		given(activeMenu.getDeletedAt()).willReturn(null);
		given(activeMenu.getStore()).willReturn(store);
		given(activeMenu.getMenuCategory()).willReturn(category);
		given(activeMenu.getCreatedAt()).willReturn(null);

		Menu deletedMenu = mock(Menu.class);
		given(deletedMenu.getId()).willReturn(2L);
		given(deletedMenu.getName()).willReturn("삭제된빵");
		given(deletedMenu.getPrice()).willReturn(2000);
		given(deletedMenu.getDescription()).willReturn(null);
		given(deletedMenu.getLikeCount()).willReturn(0);
		given(deletedMenu.getDeletedAt()).willReturn(LocalDateTime.now());
		given(deletedMenu.getStore()).willReturn(store);
		given(deletedMenu.getMenuCategory()).willReturn(category);
		given(deletedMenu.getCreatedAt()).willReturn(null);

		given(menuRepository.findByStoreIdIncludingDeleted(1L)).willReturn(List.of(activeMenu, deletedMenu));
		given(menuImageFileRepository.findThumbnailsByMenuIdIn(List.of(1L, 2L))).willReturn(Collections.emptyList());

		List<AdminMenuResponse> result = adminMenuQueryService.getStoreMenus(1L);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).status()).isEqualTo("active");
		assertThat(result.get(1).status()).isEqualTo("inactive");
	}
}
