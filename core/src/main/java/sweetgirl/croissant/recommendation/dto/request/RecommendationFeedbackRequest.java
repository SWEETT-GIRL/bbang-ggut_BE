package sweetgirl.croissant.recommendation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.FeedbackReason;

/** 추천 결과에 대한 반응 한 건. 가게 단위 반응(STORE_TAP · STORE_UNCHECK)엔 storeId, 👎(DISLIKE)엔 reason 이 붙을 수 있다. */
public record RecommendationFeedbackRequest(
		@Schema(description = "반응", example = "SAVE") @NotNull(message = "반응은 필수입니다.") FeedbackAction action,
		@Schema(description = "가게 단위 반응일 때의 가게 ID", example = "1042") Long storeId,
		@Schema(description = "👎 의 이유 칩. DISLIKE 에만", example = "TOO_FAR") FeedbackReason reason) {
}
