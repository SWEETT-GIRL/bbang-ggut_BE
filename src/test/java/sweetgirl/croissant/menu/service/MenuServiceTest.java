package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.RecommendMenu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.repository.RecommendMenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	RecommendMenuRepository recommendMenuRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;

	@InjectMocks
	MenuService menuService;

	@Test
	@DisplayName("recommendMenu: 정상 요청이면 RecommendMenu를 저장하고 recommendCount를 1 올린다")
	void recommendMenu_validRequest_savesRecommendMenuAndIncrementsCount() {
		Menu menu = menu(1L);
		Member memberRef = member(99L);

		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(recommendMenuRepository.existsByMemberIdAndMenuId(99L, 1L)).willReturn(false);
		given(memberRepository.getReferenceById(99L)).willReturn(memberRef);

		menuService.recommendMenu(1L, 99L);

		verify(recommendMenuRepository).save(any(RecommendMenu.class));
		verify(menuRepository).incrementRecommendCount(1L);
	}

	@Test
	@DisplayName("recommendMenu: 이미 추천한 메뉴이면 ALREADY_RECOMMENDED를 던지고 저장하지 않는다")
	void recommendMenu_alreadyRecommended_throwsAlreadyRecommended() {
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu(1L)));
		given(recommendMenuRepository.existsByMemberIdAndMenuId(99L, 1L)).willReturn(true);

		assertThatThrownBy(() -> menuService.recommendMenu(1L, 99L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.ALREADY_RECOMMENDED.getMessage());

		verify(recommendMenuRepository, never()).save(any());
		verify(menuRepository, never()).incrementRecommendCount(any());
	}

	@Test
	@DisplayName("recommendMenu: 존재하지 않는 메뉴이면 MENU_NOT_FOUND를 던진다")
	void recommendMenu_menuNotFound_throwsMenuNotFound() {
		given(menuRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuService.recommendMenu(99L, 1L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_NOT_FOUND.getMessage());

		verify(recommendMenuRepository, never()).save(any());
		verify(menuRepository, never()).incrementRecommendCount(any());
	}

	@Test
	@DisplayName("cancelMenuRecommend: 정상 요청이면 RecommendMenu를 삭제하고 recommendCount를 1 내린다")
	void cancelMenuRecommend_validRequest_deletesRecommendMenuAndDecrementsCount() {
		Menu menu = menu(1L);
		RecommendMenu recommendMenu = RecommendMenu.builder().menu(menu).member(member(99L)).build();

		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(recommendMenuRepository.findByMemberIdAndMenuId(99L, 1L)).willReturn(Optional.of(recommendMenu));

		menuService.cancelMenuRecommend(1L, 99L);

		verify(recommendMenuRepository).delete(recommendMenu);
		verify(menuRepository).decrementRecommendCount(1L);
	}

	@Test
	@DisplayName("cancelMenuRecommend: 추천하지 않은 메뉴이면 NOT_RECOMMENDED를 던지고 삭제하지 않는다")
	void cancelMenuRecommend_notRecommended_throwsNotRecommended() {
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu(1L)));
		given(recommendMenuRepository.findByMemberIdAndMenuId(99L, 1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuService.cancelMenuRecommend(1L, 99L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.NOT_RECOMMENDED.getMessage());

		verify(recommendMenuRepository, never()).delete(any());
		verify(menuRepository, never()).decrementRecommendCount(any());
	}

	@Test
	@DisplayName("cancelMenuRecommend: 존재하지 않는 메뉴이면 MENU_NOT_FOUND를 던진다")
	void cancelMenuRecommend_menuNotFound_throwsMenuNotFound() {
		given(menuRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuService.cancelMenuRecommend(99L, 1L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_NOT_FOUND.getMessage());

		verify(recommendMenuRepository, never()).delete(any());
		verify(menuRepository, never()).decrementRecommendCount(any());
	}

	private Menu menu(Long id) {
		Store store = Store.builder()
			.name("단팥당")
			.address("서울 마포구")
			.build();
		Menu menu = Menu.builder()
			.store(store)
			.menuCategory(mock(MenuCategory.class))
			.name("소금빵")
			.price(3000)
			.build();
		ReflectionTestUtils.setField(menu, "id", id);
		return menu;
	}

	private Member member(Long id) {
		Member member = Member.builder()
			.nickname("슈크림")
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
