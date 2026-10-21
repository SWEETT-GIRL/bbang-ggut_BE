package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

@ExtendWith(MockitoExtension.class)
class BreadCategorySeederTest {

	@Mock
	private BreadCategoryRepository categoryRepository;

	@InjectMocks
	private BreadCategorySeeder seeder;

	@Test
	@DisplayName("기본 목록에 빠진 MenuKind 만 대분류와 함께 채운다 — 빵·기타는 넣지 않는다")
	void seedsMissingKindsOnly() {
		List<MenuKind> existing = new ArrayList<>(List.of(MenuKind.values()));
		existing.remove(MenuKind.소금빵);
		existing.remove(MenuKind.빙수);
		existing.remove(MenuKind.빵);
		existing.remove(MenuKind.기타);
		given(categoryRepository.findAllMenuKinds()).willReturn(existing);

		seeder.run(null);

		verify(categoryRepository, times(2)).save(any(BreadCategory.class));
		verify(categoryRepository).save(argThat((BreadCategory category) -> category.getMenuKind() == MenuKind.소금빵
			&& category.getBreadGroup() == BreadGroup.빵
			&& category.getName().equals("소금빵") && category.isDefault()));
		verify(categoryRepository).save(argThat((BreadCategory category) -> category.getMenuKind() == MenuKind.빙수
			&& category.getBreadGroup() == BreadGroup.빙수_아이스));
	}

	@Test
	@DisplayName("다 있으면 아무것도 안 넣는다")
	void nothingToSeed() {
		List<MenuKind> existing = new ArrayList<>(List.of(MenuKind.values()));
		existing.removeAll(MenuCategory.UNCATEGORIZED_KINDS);
		given(categoryRepository.findAllMenuKinds()).willReturn(existing);

		seeder.run(null);

		verify(categoryRepository, never()).save(any());
		assertThat(existing).hasSize(89);
	}
}
