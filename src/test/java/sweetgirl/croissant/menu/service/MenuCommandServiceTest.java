package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.external.opensearch.event.StoreMenusChangedEvent;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.request.MenuCreateRequest;
import sweetgirl.croissant.menu.dto.response.MenuRegistrationResult;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuLike;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class MenuCommandServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuLikeRepository menuLikeRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	MemberReader memberReader;
	@Mock
	MemberRestrictionReader memberRestrictionReader;
	@Mock
	StoreRepository storeRepository;
	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	MenuCommandService menuCommandService;

	@Test
	@DisplayName("registerMenu: 이름만으로 카테고리 없는 메뉴를 즉시 등록한다")
	void registerMenu_newName_createsMenu() {
		Member member = member(99L);
		Store store = Store.builder().name("단팥당").address("서울 마포구").build();
		ReflectionTestUtils.setField(store, "id", 10L);
		given(memberReader.getMember(99L)).willReturn(member);
		given(storeRepository.findByIdForUpdate(10L)).willReturn(Optional.of(store));
		given(menuRepository.findByStoreIdAndNormalizedNameAndDeletedAtIsNull(10L, "흑임자소금빵"))
			.willReturn(Optional.empty());
		given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> {
			Menu menu = invocation.getArgument(0);
			ReflectionTestUtils.setField(menu, "id", 3L);
			return menu;
		});

		MenuRegistrationResult result = menuCommandService.registerMenu(
			10L, 99L, new MenuCreateRequest("  흑임자 소금빵  "));

		assertThat(result).isEqualTo(new MenuRegistrationResult(3L, true));
		verify(menuRepository).save(argThat(menu -> menu.getName().equals("흑임자 소금빵")
			&& menu.getMenuCategory() == null
			&& menu.getCreatedByMember() == member));
		ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue()).isEqualTo(new StoreMenusChangedEvent(10L));
	}

	@Test
	@DisplayName("registerMenu: 공백과 대소문자만 다른 활성 메뉴가 있으면 기존 메뉴를 반환한다")
	void registerMenu_duplicateName_returnsExistingMenu() {
		Member member = member(99L);
		Store store = Store.builder().name("단팥당").address("서울 마포구").build();
		Menu existing = menu(3L);
		given(memberReader.getMember(99L)).willReturn(member);
		given(storeRepository.findByIdForUpdate(10L)).willReturn(Optional.of(store));
		given(menuRepository.findByStoreIdAndNormalizedNameAndDeletedAtIsNull(10L, "plaincroissant"))
			.willReturn(Optional.of(existing));

		MenuRegistrationResult result = menuCommandService.registerMenu(
			10L, 99L, new MenuCreateRequest("Plain Croissant"));

		assertThat(result).isEqualTo(new MenuRegistrationResult(3L, false));
		verify(menuRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

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
