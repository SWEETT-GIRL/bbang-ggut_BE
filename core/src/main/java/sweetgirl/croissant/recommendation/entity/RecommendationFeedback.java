package sweetgirl.croissant.recommendation.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.FeedbackReason;
import sweetgirl.croissant.recommendation.exception.RecommendationException;

/**
 * 추천 결과에 사용자가 보인 반응 (설계 §3.1 · §6 POST /recommendations/{id}/feedback).
 *
 * 가게 단위 반응(STORE_TAP · STORE_UNCHECK)은 storeId 가 필수고, 나머지는 오면 남긴다 — 앵커 카드의 X(DISMISS)와 담기(SAVE)는
 * 가게를 싣고 오고, 줄 전체 닫기와 결과 시트의 반응은 싣지 않는다. 결과 시트의 👍/👎(LIKE · DISLIKE)는 코스 단위 명시 신호고,
 * 👎 에만 이유 칩(reason)이 붙는다 — 이것이 있어야 provider · promptVersion · fallback 별로 만족도를 비교할 수 있다.
 * 한 번 쓰면 바꾸지 않는다.
 */
@Entity
@Table(name = "recommendation_feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationFeedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recommendation_id", nullable = false)
	private Long recommendationId;

	@Column(name = "store_id")
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FeedbackAction action;

	/** 👎 의 이유 칩. 칩을 고르지 않으면 null. */
	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private FeedbackReason reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private RecommendationFeedback(Long recommendationId, Long storeId, FeedbackAction action, FeedbackReason reason) {
		this.recommendationId = recommendationId;
		this.storeId = storeId;
		this.action = action;
		this.reason = reason;
		this.createdAt = LocalDateTime.now();
	}

	public static RecommendationFeedback of(Long recommendationId, Long storeId, FeedbackAction action) {
		return of(recommendationId, storeId, action, null);
	}

	/** 피드백 API 요청 본문 {action, storeId, reason} 과 같은 모양. storeId 는 가게 단위 반응에만 필수이고, 오면 어떤 반응이든 남긴다. */
	public static RecommendationFeedback of(
			Long recommendationId, Long storeId, FeedbackAction action, FeedbackReason reason) {
		if (action.isStoreScoped() && storeId == null) {
			throw new RecommendationException(RecommendationErrorCode.FEEDBACK_STORE_REQUIRED);
		}
		if (reason != null && !action.acceptsReason()) {
			throw new RecommendationException(RecommendationErrorCode.FEEDBACK_REASON_NOT_ALLOWED);
		}
		return new RecommendationFeedback(recommendationId, storeId, action, reason);
	}
}
