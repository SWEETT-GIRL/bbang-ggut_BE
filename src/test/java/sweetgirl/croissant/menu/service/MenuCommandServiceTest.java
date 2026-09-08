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
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.dto.request.MenuCreateRequest;
import sweetgirl.croissant.menu.dto.response.MenuRegistrationResult;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuLike;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
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
	MenuNameClassificationRepository menuNameClassificationRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;
	@Mock
	MenuKindClassifier menuKindClassifier;

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

		assertThat(result).isEqualTo(new MenuRegistrationResult(3L, true, null));
		verify(menuRepository).save(argThat(menu -> menu.getName().equals("흑임자 소금빵")
			&& menu.getMenuCategory() == null
			&& menu.getCreatedByMember() == member));
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

		assertThat(result).isEqualTo(new MenuRegistrationResult(3L, false, null));
		verify(menuRepository, never()).save(any());
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

	@Test
	@DisplayName("registerMenu: 캐시에 3회 일치 판정이 있으면 등록 즉시 카테고리를 쓰고 그 kind 를 제안한다")
	void registerMenu_cachedVerdict_appliesImmediately() {
		Store store = Store.builder().name("단팥당").address("서울 마포구").build();
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findByIdForUpdate(1L)).willReturn(Optional.of(store));
		given(menuRepository.findByStoreIdAndNormalizedNameAndDeletedAtIsNull(1L, "사각카스테라초코"))
			.willReturn(Optional.empty());
		given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(menuNameClassificationRepository.findById("사각카스테라초코")).willReturn(Optional.of(
			MenuNameClassification.from(MenuKindVerdict.agreed("사각카스테라초코", MenuKind.카스테라, 0.95), "m")));
		MenuCategory castella = MenuCategory.builder().name(MenuKind.카스테라).build();
		given(menuCategoryRepository.findByName(MenuKind.카스테라)).willReturn(Optional.of(castella));

		MenuRegistrationResult result = menuCommandService.registerMenu(1L, 7L, new MenuCreateRequest("사각 카스테라(초코)"));

		assertThat(result.created()).isTrue();
		assertThat(result.suggestedKind()).isEqualTo("카스테라");
		verify(menuKindClassifier, never()).suggest(any());
		ArgumentCaptor<Menu> saved = ArgumentCaptor.forClass(Menu.class);
		verify(menuRepository).save(saved.capture());
		assertThat(saved.getValue().getMenuCategory()).isSameAs(castella);
		assertThat(saved.getValue().getBakeryItem()).isTrue();
		assertThat(saved.getValue().getClassifiedAt()).isNotNull();
	}

	@Test
	@DisplayName("registerMenu: 처음 보는 이름은 1회 즉답을 제안만 하고 카테고리는 비워 둔다 — 확정은 스케줄러가")
	void registerMenu_unknownName_suggestsWithoutSaving() {
		Store store = Store.builder().name("단팥당").address("서울 마포구").build();
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findByIdForUpdate(1L)).willReturn(Optional.of(store));
		given(menuRepository.findByStoreIdAndNormalizedNameAndDeletedAtIsNull(1L, "무화과크림치즈"))
			.willReturn(Optional.empty());
		given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(menuNameClassificationRepository.findById("무화과크림치즈")).willReturn(Optional.empty());
		given(menuKindClassifier.suggest("무화과크림치즈")).willReturn(Optional.of(MenuKind.소금빵));

		MenuRegistrationResult result = menuCommandService.registerMenu(1L, 7L, new MenuCreateRequest("무화과크림치즈"));

		assertThat(result.suggestedKind()).isEqualTo("소금빵");
		ArgumentCaptor<Menu> saved = ArgumentCaptor.forClass(Menu.class);
		verify(menuRepository).save(saved.capture());
		assertThat(saved.getValue().getMenuCategory()).isNull();
		assertThat(saved.getValue().getClassifiedAt()).isNull();
	}

	@Test
	@DisplayName("registerMenu: 캐시에 갈린 판정이 있으면 다시 묻지 않고 제안도 없다")
	void registerMenu_undecidedCache_noSuggestion() {
		Store store = Store.builder().name("단팥당").address("서울 마포구").build();
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findByIdForUpdate(1L)).willReturn(Optional.of(store));
		given(menuRepository.findByStoreIdAndNormalizedNameAndDeletedAtIsNull(1L, "호두랑호두"))
			.willReturn(Optional.empty());
		given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(menuNameClassificationRepository.findById("호두랑호두")).willReturn(Optional.of(
			MenuNameClassification.from(MenuKindVerdict.undecided("호두랑호두", 0.5), "m")));

		MenuRegistrationResult result = menuCommandService.registerMenu(1L, 7L, new MenuCreateRequest("호두랑 호두"));

		assertThat(result.suggestedKind()).isNull();
		verify(menuKindClassifier, never()).suggest(any());
		verify(menuCategoryRepository, never()).findByName(any());
	}

	@Test
	@DisplayName("confirmKind: 본인이 등록한 메뉴면 kind 를 확정한다")
	void confirmKind_owner_applies() {
		Member owner = member(7L);
		Menu menu = Menu.builder()
			.store(Store.builder().name("단팥당").address("서울 마포구").build())
			.name("무화과크림치즈")
			.createdByMember(owner)
			.build();
		given(menuRepository.findById(3L)).willReturn(Optional.of(menu));
		MenuCategory salt = MenuCategory.builder().name(MenuKind.소금빵).build();
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(salt));

		menuCommandService.confirmKind(3L, 7L, "소금빵");

		assertThat(menu.getMenuCategory()).isSameAs(salt);
		assertThat(menu.getBakeryItem()).isTrue();
		verify(menuNameClassificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("confirmKind: 남이 등록한 메뉴면 MENU_NOT_OWNER")
	void confirmKind_notOwner_throws() {
		Menu menu = Menu.builder()
			.store(Store.builder().name("단팥당").address("서울 마포구").build())
			.name("무화과크림치즈")
			.createdByMember(member(99L))
			.build();
		given(menuRepository.findById(3L)).willReturn(Optional.of(menu));

		assertThatThrownBy(() -> menuCommandService.confirmKind(3L, 7L, "소금빵"))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_NOT_OWNER.getMessage());
		assertThat(menu.getMenuCategory()).isNull();
	}

	@Test
	@DisplayName("confirmKind: MenuKind 에 없는 이름이면 MENU_CATEGORY_NOT_FOUND")
	void confirmKind_unknownKind_throws() {
		Menu menu = Menu.builder()
			.store(Store.builder().name("단팥당").address("서울 마포구").build())
			.name("무화과크림치즈")
			.createdByMember(member(7L))
			.build();
		given(menuRepository.findById(3L)).willReturn(Optional.of(menu));

		assertThatThrownBy(() -> menuCommandService.confirmKind(3L, 7L, "없는종류"))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_CATEGORY_NOT_FOUND.getMessage());
	}
}
