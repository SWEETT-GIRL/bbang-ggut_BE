package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

class BreadRecordTagTest {

	@Test
	@DisplayName("룰이 구체 분류를 냈으면 메뉴 분류가 달라도 룰 값을 쓴다")
	void resolvedCategory_prefersRuleWhenDecided() {
		// 밤식빵은 끝단어 원리로 식빵이다. 메뉴 쪽 AI 는 밤빵이라 하지만 룰이 답을 낸 자리는 건드리지 않는다
		BreadRecordTag tag = tag("밤식빵", "식빵", MenuKind.밤빵);

		assertThat(tag.resolvedCategory()).isEqualTo("식빵");
		assertThat(tag.uncategorized()).isFalse();
	}

	@Test
	@DisplayName("룰이 '빵'·'기타'로 물러섰으면 가리키는 메뉴의 분류를 쓴다")
	void resolvedCategory_fallsBackToMenu() {
		assertThat(tag("소금버터빵", "빵", MenuKind.소금빵).resolvedCategory()).isEqualTo("소금빵");
		assertThat(tag("잠봉뵈르", "기타", MenuKind.샌드위치).resolvedCategory()).isEqualTo("샌드위치");
	}

	@Test
	@DisplayName("메뉴도 '빵'·'기타'면 여전히 모르는 것이라 룰 값을 그대로 둔다")
	void resolvedCategory_keepsFallbackWhenMenuAlsoUndecided() {
		BreadRecordTag tag = tag("작은메아리", "기타", MenuKind.빵);

		assertThat(tag.resolvedCategory()).isEqualTo("기타");
		assertThat(tag.uncategorized()).isTrue();
	}

	@Test
	@DisplayName("메뉴를 못 가리키거나 그 메뉴가 아직 미분류면 룰 값으로 산다")
	void resolvedCategory_withoutMenu() {
		BreadRecordTag noMenu = BreadRecordTag.builder()
			.name("튀소구마")
			.breadCategory("기타")
			.positionX(0.1)
			.positionY(0.2)
			.build();
		BreadRecordTag unclassifiedMenu = tag("튀소구마", "기타", null);

		assertThat(noMenu.resolvedCategory()).isEqualTo("기타");
		assertThat(noMenu.uncategorized()).isTrue();
		assertThat(unclassifiedMenu.resolvedCategory()).isEqualTo("기타");
	}

	@Test
	@DisplayName("분류 규칙이 바뀌면 이름은 그대로 두고 분류만 갱신한다")
	void reclassify_changesCategoryOnly() {
		BreadRecordTag tag = tag("시오빵", "빵", null);

		assertThat(tag.reclassify("소금빵")).isTrue();
		assertThat(tag.reclassify("소금빵")).isFalse();
		assertThat(tag.getName()).isEqualTo("시오빵");
		assertThat(tag.getBreadCategory()).isEqualTo("소금빵");
	}

	private static BreadRecordTag tag(String name, String breadCategory, MenuKind menuKind) {
		return BreadRecordTag.builder()
			.name(name)
			.breadCategory(breadCategory)
			.positionX(0.5)
			.positionY(0.5)
			.menu(Menu.builder()
				.name(name)
				.menuCategory(menuKind == null ? null : MenuCategory.builder().name(menuKind).build())
				.build())
			.build();
	}
}
