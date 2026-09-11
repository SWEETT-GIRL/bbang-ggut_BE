package sweetgirl.croissant.store.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreCategoryTest {

	@ParameterizedTest
	@CsvSource({
		"베이커리, BAKERY",
		"'카페,디저트', CAFE_DESSERT",
		"케이크전문, CAKE",
		"'떡,한과', RICE_CAKE",
		"호두과자, WALNUT_CAKE",
		"공방, WORKSHOP",
	})
	@DisplayName("네이버 원문 카테고리를 우리 분류로 옮긴다")
	void fromExternalName_mapsNaverCategory(String raw, StoreCategory expected) {
		assertThat(StoreCategory.fromExternalName(raw)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({
		"'음식점 > 양식 > 베이커리', BAKERY",
		"'음식점 > 간식 > 도넛', DONUT",
		"'음식점 > 간식 > 제과,베이커리 > 파리바게뜨', BAKERY",
		"'카페 > 디저트카페', CAFE_DESSERT",
		"'음식점 > 카페 > 커피전문점 > 스타벅스', CAFE_DESSERT",
	})
	@DisplayName("카카오는 경로로 오므로 잎에서 뿌리 방향으로 훑는다 — 잎이 브랜드면 상위 마디가 잡힌다")
	void fromExternalName_walksKakaoPathFromLeaf(String raw, StoreCategory expected) {
		assertThat(StoreCategory.fromExternalName(raw)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	@DisplayName("카테고리를 못 받았으면 null — OTHER 와 구분한다")
	void fromExternalName_returnsNull_whenNotCollected(String raw) {
		assertThat(StoreCategory.fromExternalName(raw)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"방면정보", "전기차충전소", "꽃집,꽃배달", "네이버가 내일 새로 만들 카테고리"})
	@DisplayName("값은 받았는데 매핑이 없으면 OTHER — 모르는 값이 와도 터지지 않는다")
	void fromExternalName_returnsOther_whenUnmapped(String raw) {
		assertThat(StoreCategory.fromExternalName(raw)).isEqualTo(StoreCategory.OTHER);
	}

	@Test
	@DisplayName("enum 이름은 DB 에 그대로 들어가므로 컬럼 길이(20)를 넘지 않는다")
	void names_fitInColumnLength() {
		assertThat(StoreCategory.values())
			.allSatisfy(category -> assertThat(category.name().length()).isLessThanOrEqualTo(20));
	}
}
