package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BreadCategoryClassifierTest {

	private final BreadCategoryClassifier classifier = new BreadCategoryClassifier();

	@Test
	@DisplayName("사용자가 입력한 이름에 포함된 빵 종류를 분류한다")
	void classify_matchesContainedCategory() {
		assertThat(classifier.classify("밀도 크루아상")).isEqualTo("크루아상");
		assertThat(classifier.classify("우유식빵")).isEqualTo("식빵");
	}

	@Test
	@DisplayName("공백을 제거하고 긴 분류명을 우선 매칭한다")
	void classify_ignoresWhitespaceAndPrefersLongerCategory() {
		assertThat(classifier.classify("쫀득 쿠키")).isEqualTo("쫀득쿠키");
		assertThat(classifier.classify("샌드 베이글")).isEqualTo("샌드베이글");
		assertThat(classifier.classify("에그 타르트")).isEqualTo("에그타르트");
	}

	@Test
	@DisplayName("분류할 수 없는 이름은 기타로 저장한다")
	void classify_returnsFallbackForUnknownName() {
		assertThat(classifier.classify("더티 초코")).isEqualTo("기타");
		assertThat(classifier.classify(null)).isEqualTo("기타");
	}
}
