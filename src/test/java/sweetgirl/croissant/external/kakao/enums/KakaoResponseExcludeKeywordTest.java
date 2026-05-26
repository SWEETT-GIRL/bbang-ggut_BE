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
	@DisplayName("공간 임대형 카페는 제외 대상이다 (스터디/키즈/북/만화/보드게임/룸)")
	void matches_true_whenSpaceRentalCafe() {
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 스터디카페")).isTrue();
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 키즈카페")).isTrue();
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 북카페")).isTrue();
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 만화카페")).isTrue();
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 보드게임카페")).isTrue();
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 룸카페")).isTrue();
	}

	@Test
	@DisplayName("반려동물카페는 제외 대상이다")
	void matches_true_whenPetCafe() {
		assertThat(KakaoResponseExcludeKeyword.matches("음식점 > 카페 > 반려동물카페")).isTrue();
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
