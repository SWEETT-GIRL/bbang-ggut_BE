package sweetgirl.croissant.menu.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.menu.enums.MenuKind;

class MenuCategoryAxisTest {

	@Test
	@DisplayName("축 값이 들어오기 전엔 근거 없음이다 — NULL 은 0(중간)이 아니다")
	void newCategory_hasNoAxis() {
		MenuCategory saltBread = MenuCategory.builder().name(MenuKind.소금빵).build();

		assertThat(saltBread.hasAxis()).isFalse();
	}

	@Test
	@DisplayName("네 축이 모두 있어야 축 계산에 넣는다")
	void hasAxis_requiresAllFour() {
		MenuCategory saltBread = MenuCategory.builder().name(MenuKind.소금빵).build();
		ReflectionTestUtils.setField(saltBread, "axisTaste", (short)1);
		ReflectionTestUtils.setField(saltBread, "axisTexture", (short)1);
		ReflectionTestUtils.setField(saltBread, "axisFilling", (short)1);

		assertThat(saltBread.hasAxis()).isFalse();

		ReflectionTestUtils.setField(saltBread, "axisVibe", (short)1);

		assertThat(saltBread.hasAxis()).isTrue();
	}
}
