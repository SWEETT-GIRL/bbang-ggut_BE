package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
import sweetgirl.croissant.menu.entity.MenuLike;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class MenuCommandServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuLikeRepository menuLikeRepository;
	@Mock
	MemberRepository memberRepository;

	@InjectMocks
	MenuCommandService menuCommandService;

	@Test
	@DisplayName("likeMenu: 정상 요청이면 MenuLike를 저장하고 likeCount를 1 올린다")
	void likeMenu_validRequest_savesMenuLikeAndIncrementsCount() {
		Menu menu = menu(1L);
		Member memberRef = member(99L);

		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(menuLikeRepository.existsByMemberIdAndMenuId(99L, 1L)).willReturn(false);
		given(memberRepository.getReferenceById(99L)).willReturn(memberRef);

		menuCommandService.likeMenu(1L, 99L);

		verify(menuLikeRepository).save(any(MenuLike.class));
		verify(menuRepository).incrementLikeCount(1L);
	}

	@Test
	@DisplayName("likeMenu: 이미 좋아요한 메뉴이면 ALREADY_LIKED를 던지고 저장하지 않는다")
	void likeMenu_alreadyLiked_throwsAlreadyLiked() {
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu(1L)));
		given(menuLikeRepository.existsByMemberIdAndMenuId(99L, 1L)).willReturn(true);

		assertThatThrownBy(() -> menuCommandService.likeMenu(1L, 99L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.ALREADY_LIKED.getMessage());

		verify(menuLikeRepository, never()).save(any());
		verify(menuRepository, never()).incrementLikeCount(any());
	}

	@Test
	@DisplayName("likeMenu: 존재하지 않는 메뉴이면 MENU_NOT_FOUND를 던진다")
	void likeMenu_menuNotFound_throwsMenuNotFound() {
		given(menuRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuCommandService.likeMenu(99L, 1L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_NOT_FOUND.getMessage());

		verify(menuLikeRepository, never()).save(any());
		verify(menuRepository, never()).incrementLikeCount(any());
	}

	@Test
	@DisplayName("cancelMenuLike: 정상 요청이면 MenuLike를 삭제하고 likeCount를 1 내린다")
	void cancelMenuLike_validRequest_deletesMenuLikeAndDecrementsCount() {
		Menu menu = menu(1L);
		MenuLike menuLike = MenuLike.builder().menu(menu).member(member(99L)).build();

		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(menuLikeRepository.findByMemberIdAndMenuId(99L, 1L)).willReturn(Optional.of(menuLike));

		menuCommandService.cancelMenuLike(1L, 99L);

		verify(menuLikeRepository).delete(menuLike);
		verify(menuRepository).decrementLikeCount(1L);
	}

	@Test
	@DisplayName("cancelMenuLike: 좋아요하지 않은 메뉴이면 NOT_LIKED를 던지고 삭제하지 않는다")
	void cancelMenuLike_notLiked_throwsNotLiked() {
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu(1L)));
		given(menuLikeRepository.findByMemberIdAndMenuId(99L, 1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuCommandService.cancelMenuLike(1L, 99L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.NOT_LIKED.getMessage());

		verify(menuLikeRepository, never()).delete(any());
		verify(menuRepository, never()).decrementLikeCount(any());
	}

	@Test
	@DisplayName("cancelMenuLike: 존재하지 않는 메뉴이면 MENU_NOT_FOUND를 던진다")
	void cancelMenuLike_menuNotFound_throwsMenuNotFound() {
		given(menuRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuCommandService.cancelMenuLike(99L, 1L))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_NOT_FOUND.getMessage());

		verify(menuLikeRepository, never()).delete(any());
		verify(menuRepository, never()).decrementLikeCount(any());
	}

	private Menu menu(Long id) {
		Store store = Store.builder()
			.name("단팥당")
			.address("서울 마포구")
			.build();
		Menu menu = Menu.builder()
			.store(store)
			.menuCategory(MenuCategory.builder().name(MenuKind.빵).build())
			.name("소금빵")
			.price(3000)
			.build();
		ReflectionTestUtils.setField(menu, "id", id);
		ReflectionTestUtils.setField(menu, "likeCount", 0);
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
