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
import sweetgirl.croissant.recommendation.enums.FeedbackAction;

/**
 * 추천 결과에 사용자가 보인 반응 (설계 §3.1 · §6 POST /recommendations/{id}/feedback).
 *
 * 가게 단위 반응(STORE_TAP · STORE_UNCHECK)만 storeId 를 갖는다. 한 번 쓰면 바꾸지 않는다.
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

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private RecommendationFeedback(Long recommendationId, Long storeId, FeedbackAction action) {
		this.recommendationId = recommendationId;
		this.storeId = storeId;
		this.action = action;
		this.createdAt = LocalDateTime.now();
	}

	public static RecommendationFeedback of(Long recommendationId, Long storeId, FeedbackAction action) {
		if (action.isStoreScoped() && storeId == null) {
			throw new IllegalArgumentException("가게 단위 반응에는 가게 ID가 필요합니다: " + action);
		}
		return new RecommendationFeedback(recommendationId, action.isStoreScoped() ? storeId : null, action);
	}
}
