package sweetgirl.croissant.recommendation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.FeedbackReason;
import sweetgirl.croissant.recommendation.exception.RecommendationException;

class RecommendationFeedbackTest {

	@Test
	@DisplayName("가게 단위 반응은 가게 ID 를 남긴다")
	void of_storeScopedKeepsStoreId() {
		RecommendationFeedback uncheck = RecommendationFeedback.of(99L, 1042L, FeedbackAction.STORE_UNCHECK);

		assertThat(uncheck.getStoreId()).isEqualTo(1042L);
		assertThat(uncheck.getAction()).isEqualTo(FeedbackAction.STORE_UNCHECK);
		assertThat(uncheck.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("추천 전체에 대한 반응은 가게 ID 를 버린다")
	void of_recommendationScopedDropsStoreId() {
		RecommendationFeedback dismiss = RecommendationFeedback.of(99L, 1042L, FeedbackAction.DISMISS);

		assertThat(dismiss.getStoreId()).isNull();
	}

	@Test
	@DisplayName("👎 에는 이유 칩을 붙일 수 있고, 코스 단위라 가게 ID 는 버린다")
	void of_dislikeKeepsReason() {
		RecommendationFeedback dislike = RecommendationFeedback.of(99L, 1042L, FeedbackAction.DISLIKE,
			FeedbackReason.TOO_FAR);

		assertThat(dislike.getReason()).isEqualTo(FeedbackReason.TOO_FAR);
		assertThat(dislike.getStoreId()).isNull();
	}

	@Test
	@DisplayName("👎 를 칩 없이 눌러도 남는다")
	void of_dislikeWithoutReason() {
		RecommendationFeedback dislike = RecommendationFeedback.of(99L, null, FeedbackAction.DISLIKE);

		assertThat(dislike.getAction()).isEqualTo(FeedbackAction.DISLIKE);
		assertThat(dislike.getReason()).isNull();
	}

	@Test
	@DisplayName("👎 가 아닌 반응에 이유가 붙으면 만들 수 없다")
	void of_reasonOnlyWithDislike() {
		assertThatThrownBy(() -> RecommendationFeedback.of(99L, null, FeedbackAction.LIKE, FeedbackReason.OTHER))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.FEEDBACK_REASON_NOT_ALLOWED));
		assertThatThrownBy(() -> RecommendationFeedback.of(99L, 1042L, FeedbackAction.STORE_TAP, FeedbackReason.OTHER))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.FEEDBACK_REASON_NOT_ALLOWED));
	}

	@Test
	@DisplayName("가게 단위 반응인데 가게 ID 가 없으면 만들 수 없다")
	void of_storeScopedWithoutStoreId() {
		assertThatThrownBy(() -> RecommendationFeedback.of(99L, null, FeedbackAction.STORE_TAP))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.FEEDBACK_STORE_REQUIRED));
	}
}
