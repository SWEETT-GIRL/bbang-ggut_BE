package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
class AdminMenuCommandServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	AdminMenuCommandService adminMenuCommandService;

	@Test
	@DisplayName("createMenu: 가격을 Integer로 파싱해 메뉴를 저장한다")
	void createMenu_parsesPrice_savesMenu() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn(MenuKind.소금빵);
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(category));

		Menu saved = mock(Menu.class);
		given(saved.getId()).willReturn(10L);
		given(saved.getName()).willReturn("소금빵");
		given(saved.getPrice()).willReturn(3000);
		given(saved.getDescription()).willReturn(null);
		given(saved.getLikeCount()).willReturn(0);
		given(saved.getDeletedAt()).willReturn(null);
		given(saved.getStore()).willReturn(store);
		given(saved.getMenuCategory()).willReturn(category);
		given(saved.getCreatedAt()).willReturn(null);
		given(menuRepository.save(any(Menu.class))).willReturn(saved);
		given(menuImageFileRepository.findImagesByMenuId(10L)).willReturn(Collections.emptyList());

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "소금빵", "3000원", null, "소금빵", "active");

		AdminMenuResponse response = adminMenuCommandService.createMenu(1L, request);

		assertThat(response.price()).isEqualTo("3000원");
		assertThat(response.bakeryId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("createMenu: 같은 store 의 활성 메뉴와 정규화 이름이 일치하면 기존 메뉴를 반환한다 (idempotent)")
	void createMenu_duplicateName_returnsExisting() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn(MenuKind.소금빵);

		Menu existing = mock(Menu.class);
		given(existing.getId()).willReturn(99L);
		given(existing.getName()).willReturn("소금 빵");
		given(existing.getPrice()).willReturn(3000);
		given(existing.getDescription()).willReturn(null);
		given(existing.getLikeCount()).willReturn(0);
		given(existing.getDeletedAt()).willReturn(null);
		given(existing.getStore()).willReturn(store);
		given(existing.getMenuCategory()).willReturn(category);
		given(existing.getCreatedAt()).willReturn(null);

		given(menuRepository.findActiveByStoreId(1L)).willReturn(List.of(existing));
		given(menuImageFileRepository.findImagesByMenuId(99L)).willReturn(Collections.emptyList());

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "소금빵", "3000원", null, "소금빵", "active");

		AdminMenuResponse response = adminMenuCommandService.createMenu(1L, request);

		assertThat(response.id()).isEqualTo(99L);
		verify(menuRepository, org.mockito.Mockito.never()).save(any(Menu.class));
	}

	@Test
	@DisplayName("createMenu: 기존 메뉴와 이름이 같으면 비어있는 가격·설명을 요청 값으로 보강한다")
	void createMenu_duplicateName_enrichesMissingFields() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn(MenuKind.소금빵);

		// LocalData 로 들어온 빈 메뉴 — 가격·설명 없음.
		Menu existing = mock(Menu.class);
		given(existing.getId()).willReturn(99L);
		given(existing.getName()).willReturn("플레인 소금빵");
		given(existing.getPrice()).willReturn(null);
		given(existing.getDescription()).willReturn(null);
		given(existing.getLikeCount()).willReturn(0);
		given(existing.getDeletedAt()).willReturn(null);
		given(existing.getStore()).willReturn(store);
		given(existing.getMenuCategory()).willReturn(category);
		given(existing.getCreatedAt()).willReturn(null);

		given(menuRepository.findActiveByStoreId(1L)).willReturn(List.of(existing));
		given(menuImageFileRepository.findImagesByMenuId(99L)).willReturn(Collections.emptyList());

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "플레인소금빵", "3000원", "기본에 충실한 담백함과 바삭한 식감", "소금빵", "active");

		adminMenuCommandService.createMenu(1L, request);

		// 새로 만들지 않고(중복 방지) 기존 메뉴에 빠진 가격·설명만 채운다.
		verify(menuRepository, org.mockito.Mockito.never()).save(any(Menu.class));
		verify(existing).enrichIfMissing(3000, "기본에 충실한 담백함과 바삭한 식감");
	}

	@Test
	@DisplayName("createMenu: soft-delete 된 메뉴와 이름이 같으면 무시하고 새로 생성한다")
	void createMenu_softDeletedMatch_createsNew() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		// soft-delete 된 메뉴는 findActiveByStoreId 에서 제외되므로 빈 리스트 반환 (실제 DB 동작 모사).
		given(menuRepository.findActiveByStoreId(1L)).willReturn(Collections.emptyList());

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn(MenuKind.소금빵);
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(category));

		Menu saved = mock(Menu.class);
		given(saved.getId()).willReturn(11L);
		given(saved.getName()).willReturn("소금빵");
		given(saved.getPrice()).willReturn(3000);
		given(saved.getDescription()).willReturn(null);
		given(saved.getLikeCount()).willReturn(0);
		given(saved.getDeletedAt()).willReturn(null);
		given(saved.getStore()).willReturn(store);
		given(saved.getMenuCategory()).willReturn(category);
		given(saved.getCreatedAt()).willReturn(null);
		given(menuRepository.save(any(Menu.class))).willReturn(saved);
		given(menuImageFileRepository.findImagesByMenuId(11L)).willReturn(Collections.emptyList());

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "소금빵", "3000원", null, "소금빵", "active");

		AdminMenuResponse response = adminMenuCommandService.createMenu(1L, request);

		assertThat(response.id()).isEqualTo(11L);
		verify(menuRepository).save(any(Menu.class));
	}

	@Test
	@DisplayName("createMenu: 유효하지 않은 카테고리면 예외를 던진다")
	void createMenu_invalidCategory_throwsException() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "테스트빵", "1000원", null, "존재하지않는카테고리", "active");

		assertThatThrownBy(() -> adminMenuCommandService.createMenu(1L, request))
			.isInstanceOf(CustomException.class);
	}

	@Test
	@DisplayName("updateMenu: status가 inactive면 softDelete를 호출한다")
	void updateMenu_inactiveStatus_callsSoftDelete() {
		Menu menu = mock(Menu.class);
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

		adminMenuCommandService.updateMenu(1L, new AdminMenuUpdateRequest(null, null, null, null, "inactive"));

		verify(menu).softDelete();
	}

	@Test
	@DisplayName("updateMenu: 활성 수정이면 이름·가격·설명·카테고리를 갱신한다")
	void updateMenu_activeUpdate_updatesFields() {
		Menu menu = mock(Menu.class);
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		MenuCategory category = mock(MenuCategory.class);
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(category));

		adminMenuCommandService.updateMenu(1L,
			new AdminMenuUpdateRequest("흑임자 소금빵", "4500원", "고소함", "소금빵", "active"));

		verify(menu).update("흑임자 소금빵", 4500, "고소함", category);
		verify(menu, never()).softDelete();
	}

	@Test
	@DisplayName("deleteMenu: 메뉴를 soft-delete 한다")
	void deleteMenu_callsSoftDelete() {
		Menu menu = mock(Menu.class);
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

		adminMenuCommandService.deleteMenu(1L);

		verify(menu).softDelete();
	}

	@Test
	@DisplayName("deleteMenu: 없는 메뉴는 예외를 던진다")
	void deleteMenu_notFound_throwsException() {
		given(menuRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminMenuCommandService.deleteMenu(999L))
			.isInstanceOf(CustomException.class);
	}

	@Test
	@DisplayName("updateMenu: 없는 메뉴는 예외를 던진다")
	void updateMenu_notFound_throwsException() {
		given(menuRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminMenuCommandService.updateMenu(999L,
			new AdminMenuUpdateRequest("이름", null, null, null, "active")))
			.isInstanceOf(CustomException.class);
	}
}
