package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class MenuClassificationCommandServiceTest {

	@Mock
	private MenuRepository menuRepository;
	@Mock
	private MenuCategoryRepository menuCategoryRepository;
	@Mock
	private MenuNameClassificationRepository menuNameClassificationRepository;

	@InjectMocks
	private MenuClassificationCommandService commandService;

	private static final MenuCategory 카스테라 = MenuCategory.builder().name(MenuKind.카스테라).build();
	private static final MenuCategory 기타 = MenuCategory.builder().name(MenuKind.기타).build();

	private static Menu menu(String name) {
		return Menu.builder()
			.store(Store.builder().name("빵집").address("서울 성동구").build())
			.name(name)
			.build();
	}

	@Test
	@DisplayName("확정 판정은 카테고리·빵 여부·시각을 쓰고, 미결은 시각만 남긴다")
	void appliesDecidedAndMarksUndecided() {
		Menu castella = menu("사각 카스테라(초코)");
		Menu unknown = menu("호두랑 호두");
		given(menuRepository.findAllById(anyList())).willReturn(List.of(castella, unknown));
		given(menuCategoryRepository.findAll()).willReturn(List.of(카스테라, 기타));
		Map<String, MenuKindVerdict> verdicts = Map.of(
			"사각카스테라초코", MenuKindVerdict.agreed("사각카스테라초코", MenuKind.카스테라, 0.95),
			"호두랑호두", MenuKindVerdict.undecided("호두랑호두", 0.5));

		commandService.apply(List.of(1L, 2L), verdicts, List.of(), "gemini-test");

		assertThat(castella.getMenuCategory()).isSameAs(카스테라);
		assertThat(castella.getBakeryItem()).isTrue();
		assertThat(castella.getClassifiedAt()).isNotNull();
		assertThat(unknown.getMenuCategory()).isNull();
		assertThat(unknown.getBakeryItem()).isNull();
		assertThat(unknown.getClassifiedAt()).isNotNull();
	}

	@Test
	@DisplayName("비-빵 판정은 '기타' 카테고리에 bakeryItem=false")
	void nonBakeryGoesToEtc() {
		Menu latte = menu("허니레몬차");
		given(menuRepository.findAllById(anyList())).willReturn(List.of(latte));
		given(menuCategoryRepository.findAll()).willReturn(List.of(카스테라, 기타));

		commandService.apply(List.of(1L),
			Map.of("허니레몬차", MenuKindVerdict.nonBakery("허니레몬차", 0.9)), List.of(), "gemini-test");

		assertThat(latte.getMenuCategory()).isSameAs(기타);
		assertThat(latte.getBakeryItem()).isFalse();
	}

	@Test
	@DisplayName("새 판정은 갈린 것까지 캐시에 남긴다 — 같은 이름을 다시 안 묻게")
	void savesFreshVerdictsIncludingUndecided() {
		given(menuRepository.findAllById(anyList())).willReturn(List.of());
		given(menuCategoryRepository.findAll()).willReturn(List.of());
		List<MenuKindVerdict> fresh = List.of(
			MenuKindVerdict.agreed("소금빵", MenuKind.소금빵, 1.0),
			MenuKindVerdict.undecided("호두랑호두", 0.5));

		commandService.apply(List.of(), Map.of(), fresh, "gemini-3.5-flash-lite");

		@SuppressWarnings("unchecked") ArgumentCaptor<List<MenuNameClassification>> saved = ArgumentCaptor
			.forClass(List.class);
		verify(menuNameClassificationRepository).saveAll(saved.capture());
		assertThat(saved.getValue()).hasSize(2)
			.extracting(MenuNameClassification::getNormalizedName, MenuNameClassification::getKind,
				MenuNameClassification::isAgreed, MenuNameClassification::getModel)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple("소금빵", MenuKind.소금빵, true, "gemini-3.5-flash-lite"),
				org.assertj.core.groups.Tuple.tuple("호두랑호두", null, false, "gemini-3.5-flash-lite"));
	}

	@Test
	@DisplayName("enum 엔 있는데 menu_category 행이 없는 kind 면 미분류로 남긴다")
	void missingCategoryRowLeavesUnclassified() {
		Menu castella = menu("카스테라");
		given(menuRepository.findAllById(anyList())).willReturn(List.of(castella));
		given(menuCategoryRepository.findAll()).willReturn(List.of(기타)); // 카스테라 행 미시딩

		commandService.apply(List.of(1L),
			Map.of("카스테라", MenuKindVerdict.agreed("카스테라", MenuKind.카스테라, 1.0)), List.of(), "m");

		assertThat(castella.getMenuCategory()).isNull();
		assertThat(castella.getClassifiedAt()).isNotNull();
	}
}
