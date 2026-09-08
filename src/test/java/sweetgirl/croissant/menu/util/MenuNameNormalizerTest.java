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
	@DisplayName("특수문자 제거 — 운영 유니크 인덱스 식과 같은 키")
	void special_chars() {
		assertThat(MenuNameNormalizer.normalize("크루아상!")).isEqualTo("크루아상");
		assertThat(MenuNameNormalizer.normalize("초코·크림")).isEqualTo("초코크림");
		assertThat(MenuNameNormalizer.normalize("'얼그레이' 스콘")).isEqualTo("얼그레이스콘");
		assertThat(MenuNameNormalizer.normalize("소금빵(1개)")).isEqualTo("소금빵1개");
		assertThat(MenuNameNormalizer.normalize("[NEW] 두바이 초콜릿")).isEqualTo("new두바이초콜릿");
	}

	@Test
	@DisplayName("운영 PG 식 lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g')) 과 같은 결과")
	void matches_postgres_expression() {
		// 2026-09-07 운영 DB 에서 같은 입력으로 직접 확인한 값
		assertThat(MenuNameNormalizer.normalize("---[EGG FLEX]스크램블---")).isEqualTo("eggflex스크램블");
		assertThat(MenuNameNormalizer.normalize("Café Crème")).isEqualTo("cafécrème");
		assertThat(MenuNameNormalizer.normalize("抹茶 라떼")).isEqualTo("抹茶라떼");
	}

	@Test
	@DisplayName("특수문자만 있는 이름은 빈 문자열")
	void only_special_chars() {
		assertThat(MenuNameNormalizer.normalize("***")).isEmpty();
		assertThat(MenuNameNormalizer.normalize("---[EGG FLEX]---")).isEqualTo("eggflex");
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
			.isEqualTo(MenuNameNormalizer.normalize("소금-빵"))
			.isEqualTo(MenuNameNormalizer.normalize("소금 빵!"));
	}
}
