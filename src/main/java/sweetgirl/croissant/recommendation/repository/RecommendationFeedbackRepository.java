package sweetgirl.croissant.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {

}
