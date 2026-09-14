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
	@DisplayName("가게 단위가 아닌 반응도 가게 ID 가 오면 남긴다 — 앵커 카드의 X 는 어느 카드였는지가 신호다")
	void of_recommendationScopedKeepsStoreIdWhenGiven() {
		RecommendationFeedback cardDismiss = RecommendationFeedback.of(99L, 1042L, FeedbackAction.DISMISS);
		RecommendationFeedback rowDismiss = RecommendationFeedback.of(99L, null, FeedbackAction.DISMISS);

		assertThat(cardDismiss.getStoreId()).isEqualTo(1042L);
		assertThat(rowDismiss.getStoreId()).isNull();
	}

	@Test
	@DisplayName("👎 에는 이유 칩을 붙일 수 있다. 결과 시트는 코스 단위라 가게 ID 없이 온다")
	void of_dislikeKeepsReason() {
		RecommendationFeedback dislike = RecommendationFeedback.of(99L, null, FeedbackAction.DISLIKE,
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

	@Test
	@DisplayName("👎 이유 칩 TOO_SIMILAR(비슷한 가게뿐이에요)도 DISLIKE 에 붙는다 — 다양성 후처리를 넣을지 정하는 근거")
	void dislike_acceptsTooSimilar() {
		RecommendationFeedback feedback = RecommendationFeedback.of(99L, null, FeedbackAction.DISLIKE,
			FeedbackReason.TOO_SIMILAR);

		assertThat(feedback.getReason()).isEqualTo(FeedbackReason.TOO_SIMILAR);
		assertThat(FeedbackReason.TOO_SIMILAR.getLabel()).isEqualTo("비슷한 가게뿐이에요");
	}
}
