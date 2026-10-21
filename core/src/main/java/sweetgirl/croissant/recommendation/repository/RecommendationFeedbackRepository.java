package sweetgirl.croissant.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {
	/** 같은 추천에 이 반응이 이미 있는가 — VIEW 를 추천당 한 번만 남기는 데 쓴다(#661 재사용 규칙). */
	boolean existsByRecommendationIdAndAction(Long recommendationId, FeedbackAction action);
}
