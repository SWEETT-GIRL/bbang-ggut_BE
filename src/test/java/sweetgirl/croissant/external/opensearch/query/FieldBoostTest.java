package sweetgirl.croissant.external.opensearch.query;

import static org.assertj.core.api.Assertions.assertThat;
import static sweetgirl.croissant.external.opensearch.query.SearchSubField.BASE;
import static sweetgirl.croissant.external.opensearch.query.SearchSubField.EDGE;
import static sweetgirl.croissant.external.opensearch.query.SearchSubField.NGRAM;
import static sweetgirl.croissant.external.opensearch.query.SearchSubField.NORI;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldBoostTest {

	@Test
	@DisplayName("flat 필드는 서브필드 선언 순서대로 부스트 접미사를 붙인다")
	void boostedFields_flat_appliesBoostSuffixInSubFieldOrder() {
		FieldBoost fieldBoost = FieldBoost.flat("searchRegionAliases",
			Map.of(BASE, 4f, NORI, 3f, EDGE, 2f, NGRAM, 2f));

		assertThat(fieldBoost.isNested()).isFalse();
		assertThat(fieldBoost.boostedFields()).containsExactly(
			"searchRegionAliases^4",
			"searchRegionAliases.nori^3",
			"searchRegionAliases.edge^2",
			"searchRegionAliases.ngram^2");
	}

	@Test
	@DisplayName("부스트가 1.0이면 접미사(^)를 생략한다")
	void boostedFields_omitsSuffixWhenBoostIsOne() {
		FieldBoost fieldBoost = FieldBoost.flat("storeName", Map.of(EDGE, 2f, NGRAM, 1f));

		assertThat(fieldBoost.boostedFields()).containsExactly("storeName.edge^2", "storeName.ngram");
	}

	@Test
	@DisplayName("nested 필드는 nestedPath를 보유하고 부스트 필드를 생성한다")
	void nested_holdsNestedPathAndBuildsFields() {
		FieldBoost fieldBoost = FieldBoost.nested("menus", "menus.name", Map.of(BASE, 4f, EDGE, 3f, NGRAM, 2f));

		assertThat(fieldBoost.isNested()).isTrue();
		assertThat(fieldBoost.nestedPath()).isEqualTo("menus");
		assertThat(fieldBoost.boostedFields()).containsExactly(
			"menus.name^4", "menus.name.edge^3", "menus.name.ngram^2");
	}
}
