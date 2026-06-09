package sweetgirl.croissant.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.exception.ReviewException;

class ObjectionableContentFilterTest {

	private final ObjectionableContentFilter filter = new ObjectionableContentFilter();

	@ParameterizedTest
	@ValueSource(strings = {
		"시 발 같은 맛",
		"맘 충 출입금지",
		"010-1234-5678로 연락주세요",
		"owner@example.com",
		"900101-1234567"
	})
	@DisplayName("containsObjectionableContent: 금칙어와 개인정보 패턴을 탐지한다")
	void containsObjectionableContent_rejectedContent_returnsTrue(String content) {
		assertThat(filter.containsObjectionableContent(content)).isTrue();
	}

	@Test
	@DisplayName("containsObjectionableContent: 정상 리뷰는 통과한다")
	void containsObjectionableContent_normalReview_returnsFalse() {
		assertThat(filter.containsObjectionableContent("소금빵이 바삭하고 다시 먹고 싶어요.")).isFalse();
	}

	@Test
	@DisplayName("validate: 차단 대상 내용이면 REVIEW_CONTENT_REJECTED를 던진다")
	void validate_rejectedContent_throwsReviewContentRejected() {
		assertThatThrownBy(() -> filter.validate("01012345678"))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_CONTENT_REJECTED.getMessage());
	}
}
