package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BreadCategoryClassifierTest {

	private final BreadCategoryClassifier classifier = new BreadCategoryClassifier();

	@Test
	@DisplayName("이름에 든 빵 종류를 분류한다")
	void classify_matchesContainedCategory() {
		assertThat(classifier.classify("밀도 크루아상")).isEqualTo("크루아상");
		assertThat(classifier.classify("우유식빵")).isEqualTo("식빵");
	}

	@Test
	@DisplayName("맛 단어가 아니라 마지막 제품어가 이긴다 — 티라미수 크로와상은 크루아상")
	void classify_lastProductWordWins() {
		assertThat(classifier.classify("티라미수 크로와상")).isEqualTo("크루아상");
		assertThat(classifier.classify("맘모스치즈케이크")).isEqualTo("케이크");
		assertThat(classifier.classify("쿠키앤크림케익")).isEqualTo("케이크");
	}

	@Test
	@DisplayName("공백을 제거하고, 동률이면 긴 분류명을 우선 매칭한다")
	void classify_ignoresWhitespaceAndPrefersLongerCategory() {
		assertThat(classifier.classify("쫀득 쿠키")).isEqualTo("쫀득쿠키");
		assertThat(classifier.classify("샌드 베이글")).isEqualTo("샌드베이글");
		assertThat(classifier.classify("에그 타르트")).isEqualTo("에그타르트");
		assertThat(classifier.classify("두바이 버터떡")).isEqualTo("버터떡");
	}

	@Test
	@DisplayName("동의어·변형 표기 — 시오빵은 소금빵, 소보루는 소보로")
	void classify_resolvesSynonyms() {
		assertThat(classifier.classify("시오빵")).isEqualTo("소금빵");
		assertThat(classifier.classify("모찌시오빵")).isEqualTo("소금빵");
		assertThat(classifier.classify("옛날소보루")).isEqualTo("소보로");
		assertThat(classifier.classify("뺑오쇼콜라")).isEqualTo("페이스트리");
	}

	@Test
	@DisplayName("2026-09-07 추가 22종 — 실측에서 '빵'으로 떨어지던 품목")
	void classify_newKinds() {
		assertThat(classifier.classify("사각 카스테라(초코)")).isEqualTo("카스테라");
		assertThat(classifier.classify("단팥빵")).isEqualTo("단팥빵");
		assertThat(classifier.classify("팥붕이 붕어빵")).isEqualTo("붕어빵");
		assertThat(classifier.classify("호두과자")).isEqualTo("호두과자");
		assertThat(classifier.classify("생크림 가득 모닝빵")).isEqualTo("모닝빵");
	}

	@Test
	@DisplayName("괄호 수식은 무시한다")
	void classify_ignoresParentheses() {
		assertThat(classifier.classify("과일케이크 1호 (제철과일)")).isEqualTo("케이크");
		assertThat(classifier.classify("미니꽃설기(최소주문단위 10개)")).isEqualTo("떡");
	}

	@Test
	@DisplayName("구체 kind 가 없는 '~빵' 은 빵, 그것도 아니면 기타")
	void classify_fallback() {
		assertThat(classifier.classify("원준이엉덩이빵")).isEqualTo("빵");
		assertThat(classifier.classify("더티 초코")).isEqualTo("기타");
		assertThat(classifier.classify("아아")).isEqualTo("기타");
		assertThat(classifier.classify(null)).isEqualTo("기타");
		assertThat(classifier.classify("   ")).isEqualTo("기타");
	}
}
