package sweetgirl.croissant.menu.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MenuNameNormalizerTest {

	@Test
	@DisplayName("공백 제거 + 소문자 변환")
	void whitespace_and_case() {
		assertThat(MenuNameNormalizer.normalize("소금 빵")).isEqualTo("소금빵");
		assertThat(MenuNameNormalizer.normalize("Plain  Croissant")).isEqualTo("plaincroissant");
	}

	@Test
	@DisplayName("특수문자 제거")
	void special_chars() {
		assertThat(MenuNameNormalizer.normalize("크루아상!")).isEqualTo("크루아상");
		assertThat(MenuNameNormalizer.normalize("초코·크림")).isEqualTo("초코크림");
		assertThat(MenuNameNormalizer.normalize("'얼그레이' 스콘")).isEqualTo("얼그레이스콘");
	}

	@Test
	@DisplayName("null/빈 문자열 안전")
	void null_safe() {
		assertThat(MenuNameNormalizer.normalize(null)).isEmpty();
		assertThat(MenuNameNormalizer.normalize("")).isEmpty();
		assertThat(MenuNameNormalizer.normalize("   ")).isEmpty();
	}

	@Test
	@DisplayName("정규화된 이름이 동일하면 다른 표기여도 일치")
	void equivalence() {
		assertThat(MenuNameNormalizer.normalize("소금빵"))
			.isEqualTo(MenuNameNormalizer.normalize("소금 빵"))
			.isEqualTo(MenuNameNormalizer.normalize("소금-빵"));
	}
}
