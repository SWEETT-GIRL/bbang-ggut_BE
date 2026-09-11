package sweetgirl.croissant.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.Recommendation;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

}
