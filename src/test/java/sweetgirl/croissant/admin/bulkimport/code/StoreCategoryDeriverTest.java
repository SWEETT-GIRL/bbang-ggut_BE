package sweetgirl.croissant.admin.bulkimport.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.store.enums.StoreCategory;

class StoreCategoryDeriverTest {

	@Test
	@DisplayName("kakao category_name에 '양식'이 포함되면 WESTERN을 반환한다")
	void derive_western_whenContainsYangsik() {
		assertThat(StoreCategoryDeriver.derive("음식점 > 양식 > 베이커리"))
			.isEqualTo(StoreCategory.WESTERN);
	}

	@Test
	@DisplayName("kakao category_name에 '양식'이 없으면 null을 반환한다")
	void derive_null_whenNotContainsYangsik() {
		assertThat(StoreCategoryDeriver.derive("음식점 > 간식 > 도넛")).isNull();
		assertThat(StoreCategoryDeriver.derive("카페 > 디저트카페")).isNull();
	}

	@Test
	@DisplayName("category_name이 null이면 null을 반환한다")
	void derive_null_whenInputIsNull() {
		assertThat(StoreCategoryDeriver.derive(null)).isNull();
	}
}
