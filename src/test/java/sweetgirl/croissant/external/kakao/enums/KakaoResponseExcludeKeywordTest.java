package sweetgirl.croissant.external.kakao.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoResponseExcludeKeywordTest {

	@Test
	@DisplayName("category_name이 '패스트푸드'를 포함하면 제외 대상이다 (써브웨이/맥도날드 등)")
	void matches_true_whenContainsFastFood() {
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 패스트푸드 > 샌드위치 > 써브웨이"))
			.isTrue();
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 패스트푸드 > 햄버거"))
			.isTrue();
	}

	@Test
	@DisplayName("category_name이 제외 키워드를 포함하지 않으면 false다")
	void matches_false_whenNoExcludeKeyword() {
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 간식 > 베이커리"))
			.isFalse();
		assertThat(KakaoResponseExcludeKeyword.matches("카페 > 디저트카페"))
			.isFalse();
	}
}
