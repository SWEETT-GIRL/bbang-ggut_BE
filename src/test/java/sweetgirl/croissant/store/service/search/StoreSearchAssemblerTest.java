package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreSearchAssemblerTest {

	@Mock
	private StoreRepository storeRepository;

	@Mock
	private StoreImageFileRepository storeImageFileRepository;

	@Mock
	private MenuImageFileRepository menuImageFileRepository;

	@Mock
	private MenuRepository menuRepository;

	@Test
	void assemble_keepsEngineOrderAndPrioritizesMatchedMenus() {
		StoreSearchAssembler assembler = new StoreSearchAssembler(
			storeRepository, storeImageFileRepository, menuImageFileRepository, menuRepository);
		Store store102 = store(102L, "성수 베이크");
		Store store105 = store(105L, "타르트샵");
		StoreMenuProjection menu1 = menu(102L, 1L, "크루아상");
		StoreMenuProjection menu2 = menu(102L, 2L, "에그타르트");

		given(storeRepository.findAllById(List.of(102L, 105L))).willReturn(List.of(store105, store102));
		given(storeImageFileRepository.findFirstThumbnailsByStoreIdIn(List.of(102L, 105L))).willReturn(List.of());
		given(menuRepository.findTopRecommendedMenusByStoreIds(List.of(102L, 105L)))
			.willReturn(List.of(menu1, menu2));
		given(menuImageFileRepository.findFirstThumbnailsByMenuIdIn(List.of(2L, 1L))).willReturn(List.of());

		var result = assembler.assemble(List.of(
			new StoreSearchHit(102L, 9.0, null, List.of(2L), List.of()),
			new StoreSearchHit(105L, 8.0, null, List.of(), List.of())));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).id()).isEqualTo(102L);
		assertThat(result.get(1).id()).isEqualTo(105L);
		assertThat(result.get(0).topMenus()).extracting("menuId").containsExactly(2L, 1L);
		// 검색어에 매칭된 메뉴(2L)만 matched=true, 인기메뉴 채움용(1L)은 false
		assertThat(result.get(0).topMenus()).extracting("matched").containsExactly(true, false);
	}

	@Test
	void assemble_keepsQueryOrderAndReadsThumbnailsOnlyForSelectedMenus() {
		// 운영의 검색·지도는 매칭 메뉴를 주지 않는다 — 메뉴 쿼리가 준 순서 그대로 앞의 5개가 실리고, 썸네일은 그 5개 것만 읽는다
		StoreSearchAssembler assembler = new StoreSearchAssembler(
			storeRepository, storeImageFileRepository, menuImageFileRepository, menuRepository);
		Store store102 = store(102L, "성수 베이크");
		// 좋아요가 같은 메뉴들이라 쿼리가 준 순서가 id 순이 아니다 — 그 순서를 건드리지 않아야 한다
		List<StoreMenuProjection> menus = List.of(menu(102L, 14L, "소금빵"), menu(102L, 11L, "크루아상"),
			menu(102L, 17L, "스콘"), menu(102L, 12L, "바게트"), menu(102L, 16L, "휘낭시에"),
			menuNotSelected(13L), menuNotSelected(15L));
		MenuImageThumbnailProjection thumbnail = menuThumbnail(11L, "menu/11_320.jpg");

		given(storeRepository.findAllById(List.of(102L))).willReturn(List.of(store102));
		given(storeImageFileRepository.findFirstThumbnailsByStoreIdIn(List.of(102L))).willReturn(List.of());
		given(menuRepository.findTopRecommendedMenusByStoreIds(List.of(102L))).willReturn(menus);
		given(menuImageFileRepository.findFirstThumbnailsByMenuIdIn(List.of(14L, 11L, 17L, 12L, 16L)))
			.willReturn(List.of(thumbnail));

		var result = assembler.assemble(List.of(new StoreSearchHit(102L, 9.0, null, List.of(), List.of())));

		assertThat(result.get(0).topMenus()).extracting("menuId").containsExactly(14L, 11L, 17L, 12L, 16L);
		assertThat(result.get(0).topMenus()).extracting("matched").containsOnly(false);
		assertThat(result.get(0).topMenus()).extracting("thumbnailImageKey")
			.containsExactly(null, "menu/11_320.jpg", null, null, null);
		verify(menuImageFileRepository).findFirstThumbnailsByMenuIdIn(List.of(14L, 11L, 17L, 12L, 16L));
		verify(menuImageFileRepository, never()).findThumbnailsByMenuIdIn(anyList());
	}

	@Test
	void assemble_liftsMatchedMenuOutsideTopAndReadsItsThumbnail() {
		// 매칭된 메뉴는 좋아요 순위 밖(7번째)이어도 맨 앞으로 올라오고, 그 메뉴의 썸네일도 함께 읽힌다
		StoreSearchAssembler assembler = new StoreSearchAssembler(
			storeRepository, storeImageFileRepository, menuImageFileRepository, menuRepository);
		Store store102 = store(102L, "성수 베이크");
		List<StoreMenuProjection> menus = List.of(menu(102L, 1L, "소금빵"), menu(102L, 2L, "크루아상"),
			menu(102L, 3L, "스콘"), menu(102L, 4L, "바게트"), menuNotSelected(5L), menuNotSelected(6L),
			menu(102L, 7L, "에그타르트"));
		MenuImageThumbnailProjection thumbnail = menuThumbnail(7L, "menu/7_320.jpg");

		given(storeRepository.findAllById(List.of(102L))).willReturn(List.of(store102));
		given(storeImageFileRepository.findFirstThumbnailsByStoreIdIn(List.of(102L))).willReturn(List.of());
		given(menuRepository.findTopRecommendedMenusByStoreIds(List.of(102L))).willReturn(menus);
		given(menuImageFileRepository.findFirstThumbnailsByMenuIdIn(List.of(7L, 1L, 2L, 3L, 4L)))
			.willReturn(List.of(thumbnail));

		var result = assembler.assemble(List.of(new StoreSearchHit(102L, 9.0, null, List.of(7L), List.of())));

		assertThat(result.get(0).topMenus()).extracting("menuId").containsExactly(7L, 1L, 2L, 3L, 4L);
		assertThat(result.get(0).topMenus()).extracting("matched").containsExactly(true, false, false, false, false);
		assertThat(result.get(0).topMenus().get(0).thumbnailImageKey()).isEqualTo("menu/7_320.jpg");
	}

	private Store store(Long id, String name) {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(id);
		given(store.getName()).willReturn(name);
		given(store.getAddress()).willReturn("서울 성동구 성수동");
		return store;
	}

	// 응답에 실리지 않는 메뉴 — 이름은 읽히지 않는다
	private StoreMenuProjection menuNotSelected(Long menuId) {
		StoreMenuProjection menu = mock(StoreMenuProjection.class);
		given(menu.getStoreId()).willReturn(102L);
		given(menu.getMenuId()).willReturn(menuId);
		return menu;
	}

	private MenuImageThumbnailProjection menuThumbnail(Long menuId, String resizedKey320) {
		MenuImageThumbnailProjection thumbnail = mock(MenuImageThumbnailProjection.class);
		given(thumbnail.getMenuId()).willReturn(menuId);
		given(thumbnail.getResizedKey320()).willReturn(resizedKey320);
		return thumbnail;
	}

	private StoreMenuProjection menu(Long storeId, Long menuId, String name) {
		StoreMenuProjection menu = mock(StoreMenuProjection.class);
		given(menu.getStoreId()).willReturn(storeId);
		given(menu.getMenuId()).willReturn(menuId);
		given(menu.getName()).willReturn(name);
		return menu;
	}
}
