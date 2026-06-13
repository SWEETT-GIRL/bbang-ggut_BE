package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreHit;
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
	void assemble_keepsOpenSearchOrderAndPrioritizesMatchedMenus() {
		StoreSearchAssembler assembler = new StoreSearchAssembler(
			storeRepository, storeImageFileRepository, menuImageFileRepository, menuRepository);
		Store store102 = store(102L, "성수 베이크");
		Store store105 = store(105L, "타르트샵");
		StoreMenuProjection menu1 = menu(102L, 1L, "크루아상");
		StoreMenuProjection menu2 = menu(102L, 2L, "에그타르트");

		given(storeRepository.findAllById(List.of(102L, 105L))).willReturn(List.of(store105, store102));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(102L, 105L))).willReturn(List.of());
		given(menuRepository.findTopRecommendedMenusByStoreIds(List.of(102L, 105L)))
			.willReturn(List.of(menu1, menu2));
		given(menuImageFileRepository.findThumbnailsByMenuIdIn(List.of(1L, 2L))).willReturn(List.of());

		var result = assembler.assemble(List.of(
			new OpenSearchStoreHit(102L, 9.0, null, List.of(2L), List.of()),
			new OpenSearchStoreHit(105L, 8.0, null, List.of(), List.of())));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).id()).isEqualTo(102L);
		assertThat(result.get(1).id()).isEqualTo(105L);
		assertThat(result.get(0).topMenus()).extracting("menuId").containsExactly(2L, 1L);
		// 검색어에 매칭된 메뉴(2L)만 matched=true, 인기메뉴 채움용(1L)은 false
		assertThat(result.get(0).topMenus()).extracting("matched").containsExactly(true, false);
	}

	private Store store(Long id, String name) {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(id);
		given(store.getName()).willReturn(name);
		given(store.getAddress()).willReturn("서울 성동구 성수동");
		return store;
	}

	private StoreMenuProjection menu(Long storeId, Long menuId, String name) {
		StoreMenuProjection menu = mock(StoreMenuProjection.class);
		given(menu.getStoreId()).willReturn(storeId);
		given(menu.getMenuId()).willReturn(menuId);
		given(menu.getName()).willReturn(name);
		return menu;
	}
}
