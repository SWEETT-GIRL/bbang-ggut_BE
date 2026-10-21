package sweetgirl.croissant.breadranking.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

/** 마이그레이션의 기본 빵 종류 시딩이 {@link BreadGroup} 의 표와 어긋나지 않게 지킨다. */
class BreadCategorySeedTest {

	private static final String MIGRATION = "db/migration/V20261001150000__create_bread_ranking.sql";
	private static final Pattern ROW = Pattern.compile("\\('([^']+)', '([^']+)', '([^']+)', '([^']+)', NOW\\(\\)\\)");

	@Test
	@DisplayName("시딩 89행 = 빵·기타를 뺀 MenuKind 전부, 대분류는 BreadGroup 과 같다")
	void seedMatchesBreadGroup() throws IOException {
		String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
		Matcher matcher = ROW.matcher(sql);
		List<MenuKind> seeded = new ArrayList<>();
		while (matcher.find()) {
			MenuKind kind = MenuKind.valueOf(matcher.group(3));
			seeded.add(kind);
			assertThat(matcher.group(1)).isEqualTo(kind.name());
			assertThat(BreadGroup.of(kind)).as(kind.name()).contains(BreadGroup.valueOf(matcher.group(4)));
		}
		List<MenuKind> expected = new ArrayList<>(List.of(MenuKind.values()));
		expected.removeAll(MenuCategory.UNCATEGORIZED_KINDS);
		assertThat(seeded).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
	}
}
