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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.external.opensearch.event.StoreMenusChangedEvent;
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
	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	AdminMenuService adminMenuService;

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

		AdminMenuResponse response = adminMenuService.createMenu(1L, request);

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

		AdminMenuResponse response = adminMenuService.createMenu(1L, request);

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

		adminMenuService.createMenu(1L, request);

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

		AdminMenuResponse response = adminMenuService.createMenu(1L, request);

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

		List<AdminMenuResponse> result = adminMenuService.getStoreMenus(1L);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).status()).isEqualTo("active");
		assertThat(result.get(1).status()).isEqualTo("inactive");
	}

	@Test
	@DisplayName("updateMenu: status가 inactive면 softDelete를 호출한다")
	void updateMenu_inactiveStatus_callsSoftDelete() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		Menu menu = mock(Menu.class);
		given(menu.getStore()).willReturn(store);
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

	@Test
	@DisplayName("createMenu: 신규 메뉴 저장 시 StoreMenusChangedEvent가 해당 storeId로 발행된다")
	void createMenu_newMenu_publishesStoreMenusChangedEvent() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(menuRepository.findActiveByStoreId(1L)).willReturn(Collections.emptyList());

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

		adminMenuService.createMenu(1L, new AdminMenuCreateRequest(1L, "소금빵", "3000원", null, "소금빵", "active"));

		ArgumentCaptor<StoreMenusChangedEvent> captor = ArgumentCaptor.forClass(StoreMenusChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("createMenu: 기존 메뉴를 반환하는 idempotent 경로에서는 이벤트를 발행하지 않는다")
	void createMenu_duplicateName_noEventPublished() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn(MenuKind.소금빵);

		Menu existing = mock(Menu.class);
		given(existing.getId()).willReturn(99L);
		given(existing.getName()).willReturn("소금빵");
		given(existing.getPrice()).willReturn(3000);
		given(existing.getDescription()).willReturn(null);
		given(existing.getLikeCount()).willReturn(0);
		given(existing.getDeletedAt()).willReturn(null);
		given(existing.getStore()).willReturn(store);
		given(existing.getMenuCategory()).willReturn(category);
		given(existing.getCreatedAt()).willReturn(null);
		given(menuRepository.findActiveByStoreId(1L)).willReturn(List.of(existing));
		given(menuImageFileRepository.findImagesByMenuId(99L)).willReturn(Collections.emptyList());

		adminMenuService.createMenu(1L, new AdminMenuCreateRequest(1L, "소금빵", "3000원", null, "소금빵", "active"));

		verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
	}

	@Test
	@DisplayName("updateMenu: soft-delete 시 StoreMenusChangedEvent가 해당 storeId로 발행된다")
	void updateMenu_softDelete_publishesStoreMenusChangedEvent() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(5L);
		Menu menu = mock(Menu.class);
		given(menu.getStore()).willReturn(store);
		given(menuRepository.findById(10L)).willReturn(Optional.of(menu));

		adminMenuService.updateMenu(10L, new AdminMenuUpdateRequest(null, null, null, null, "inactive"));

		ArgumentCaptor<StoreMenusChangedEvent> captor = ArgumentCaptor.forClass(StoreMenusChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(5L);
	}

	@Test
	@DisplayName("updateMenu: 이름·카테고리 수정 시 StoreMenusChangedEvent가 해당 storeId로 발행된다")
	void updateMenu_nameUpdate_publishesStoreMenusChangedEvent() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(3L);
		Menu menu = mock(Menu.class);
		given(menu.getStore()).willReturn(store);
		given(menuRepository.findById(20L)).willReturn(Optional.of(menu));

		MenuCategory category = mock(MenuCategory.class);
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(category));

		adminMenuService.updateMenu(20L, new AdminMenuUpdateRequest("소금빵 스페셜", null, null, "소금빵", "active"));

		ArgumentCaptor<StoreMenusChangedEvent> captor = ArgumentCaptor.forClass(StoreMenusChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(3L);
	}
}
