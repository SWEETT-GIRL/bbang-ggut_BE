package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.request.AdminMenuClassificationConfirmRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuClassificationConfirmResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class AdminMenuClassificationCommandServiceTest {

	@Mock
	private MenuNameClassificationRepository menuNameClassificationRepository;
	@Mock
	private MenuCategoryRepository menuCategoryRepository;
	@Mock
	private MenuRepository menuRepository;

	@InjectMocks
	private AdminMenuClassificationCommandService service;

	private static final MenuCategory 크림빵 = MenuCategory.builder().name(MenuKind.크림빵).build();

	private static Menu menu(String name) {
		return Menu.builder().store(Store.builder().name("빵집").address("서울").build()).name(name).build();
	}

	@Test
	@DisplayName("갈렸던 캐시를 ADMIN 판정으로 덮고, 같은 이름의 미분류 메뉴에 한꺼번에 적용한다")
	void confirm_overwritesCacheAndAppliesToUnclassified() {
		MenuNameClassification undecided = MenuNameClassification.from(MenuKindVerdict.undecided("슈크림빵", 0.5),
			"gemini");
		given(menuNameClassificationRepository.findById("슈크림빵")).willReturn(Optional.of(undecided));
		given(menuCategoryRepository.findByName(MenuKind.크림빵)).willReturn(Optional.of(크림빵));
		Menu first = menu("슈크림빵");
		Menu second = menu("슈크림 빵");
		given(menuRepository.findByNormalizedNameAndMenuCategoryIsNullAndDeletedAtIsNull("슈크림빵"))
			.willReturn(List.of(first, second));

		AdminMenuClassificationConfirmResponse result = service
			.confirm(new AdminMenuClassificationConfirmRequest("슈크림 빵", "크림빵", null));

		assertThat(result.normalizedName()).isEqualTo("슈크림빵");
		assertThat(result.kind()).isEqualTo("크림빵");
		assertThat(result.bakeryItem()).isTrue();
		assertThat(result.appliedMenus()).isEqualTo(2);
		assertThat(undecided.getKind()).isEqualTo(MenuKind.크림빵);
		assertThat(undecided.isAgreed()).isTrue();
		assertThat(undecided.getModel()).isEqualTo(MenuNameClassification.ADMIN_MODEL);
		assertThat(undecided.getConfidence()).isNull();
		assertThat(first.getMenuCategory()).isSameAs(크림빵);
		assertThat(second.getMenuCategory()).isSameAs(크림빵);
		assertThat(first.getBakeryItem()).isTrue();
		verify(menuNameClassificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("캐시에 없는 이름이면 ADMIN 판정을 새로 만든다")
	void confirm_createsCacheWhenAbsent() {
		given(menuNameClassificationRepository.findById("망고요거트")).willReturn(Optional.empty());
		MenuCategory yogurt = MenuCategory.builder().name(MenuKind.요거트).build();
		given(menuCategoryRepository.findByName(MenuKind.요거트)).willReturn(Optional.of(yogurt));
		given(menuRepository.findByNormalizedNameAndMenuCategoryIsNullAndDeletedAtIsNull("망고요거트"))
			.willReturn(List.of());

		AdminMenuClassificationConfirmResponse result = service
			.confirm(new AdminMenuClassificationConfirmRequest("망고요거트", "요거트", true));

		ArgumentCaptor<MenuNameClassification> saved = ArgumentCaptor.forClass(MenuNameClassification.class);
		verify(menuNameClassificationRepository).save(saved.capture());
		assertThat(saved.getValue().getNormalizedName()).isEqualTo("망고요거트");
		assertThat(saved.getValue().getKind()).isEqualTo(MenuKind.요거트);
		assertThat(saved.getValue().getModel()).isEqualTo("ADMIN");
		assertThat(result.appliedMenus()).isZero();
	}

	@Test
	@DisplayName("비-빵으로 확정하면 bakeryItem=false 가 캐시와 메뉴에 남는다")
	void confirm_nonBakery() {
		given(menuNameClassificationRepository.findById("허니레몬차")).willReturn(Optional.empty());
		MenuCategory etc = MenuCategory.builder().name(MenuKind.기타).build();
		given(menuCategoryRepository.findByName(MenuKind.기타)).willReturn(Optional.of(etc));
		Menu tea = menu("허니레몬차");
		given(menuRepository.findByNormalizedNameAndMenuCategoryIsNullAndDeletedAtIsNull("허니레몬차"))
			.willReturn(List.of(tea));

		service.confirm(new AdminMenuClassificationConfirmRequest("허니레몬차", "기타", false));

		assertThat(tea.getMenuCategory()).isSameAs(etc);
		assertThat(tea.getBakeryItem()).isFalse();
	}

	@Test
	@DisplayName("MenuKind 에 없는 이름이면 MENU_CATEGORY_NOT_FOUND")
	void confirm_unknownKind() {
		assertThatThrownBy(() -> service.confirm(new AdminMenuClassificationConfirmRequest("소금빵", "없는종류", null)))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_CATEGORY_NOT_FOUND.getMessage());
		verify(menuNameClassificationRepository, never()).save(any());
	}
}
