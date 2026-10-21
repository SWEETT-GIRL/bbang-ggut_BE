package sweetgirl.croissant.menu.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MenuCategoryTest {

	@Test
	@DisplayName("'빵'·'기타'를 뺀 모든 MenuKind 는 어느 한 대분류에 속한다 — 빠지면 그 kind 는 트렌드에서 사라진다")
	void everyKindBelongsToACategory() {
		List<MenuKind> orphans = Arrays.stream(MenuKind.values())
			.filter(kind -> !MenuCategory.UNCATEGORIZED_KINDS.contains(kind))
			.filter(kind -> MenuCategory.of(kind).isEmpty())
			.toList();

		assertThat(orphans)
			.as("대분류에 매핑되지 않은 MenuKind — MenuCategory 에 추가할 것")
			.isEmpty();
	}

	@Test
	@DisplayName("한 MenuKind 가 두 대분류에 겹쳐 들어가지 않는다")
	void noKindInTwoCategories() {
		for (MenuKind kind : MenuKind.values()) {
			long owners = Arrays.stream(MenuCategory.values())
				.filter(category -> category.matches(kind))
				.count();
			assertThat(owners)
				.as("%s 가 속한 대분류 수", kind)
				.isLessThanOrEqualTo(1);
		}
	}

	@Test
	@DisplayName("'빵'·'기타'는 어느 대분류에도 속하지 않는다")
	void fallbackKindsStayOutside() {
		assertThat(MenuCategory.of(MenuKind.빵)).isEmpty();
		assertThat(MenuCategory.of(MenuKind.기타)).isEmpty();
	}

	@Test
	@DisplayName("2026-09-07 에 자리를 찾은 kind 들")
	void previouslyOrphanedKindsNowMapped() {
		assertThat(MenuCategory.of(MenuKind.식빵)).contains(MenuCategory.식빵);
		assertThat(MenuCategory.of(MenuKind.단팥빵)).contains(MenuCategory.단과자빵);
		assertThat(MenuCategory.of(MenuKind.크림빵)).contains(MenuCategory.단과자빵);
		assertThat(MenuCategory.of(MenuKind.떡)).contains(MenuCategory.떡한과);
		assertThat(MenuCategory.of(MenuKind.약과)).contains(MenuCategory.떡한과);
		assertThat(MenuCategory.of(MenuKind.카스테라)).contains(MenuCategory.케이크);
		assertThat(MenuCategory.of(MenuKind.호두과자)).contains(MenuCategory.구움과자);
		assertThat(MenuCategory.of(MenuKind.아이스크림)).contains(MenuCategory.빙수);
	}
}
