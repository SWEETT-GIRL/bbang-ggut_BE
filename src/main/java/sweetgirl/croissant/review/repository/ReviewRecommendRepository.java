package sweetgirl.croissant.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.review.entity.ReviewRecommend;

public interface ReviewRecommendRepository extends JpaRepository<ReviewRecommend, Long> {

	@Query("""
			SELECT rr.review.id FROM ReviewRecommend rr
			WHERE rr.member.id = :memberId AND rr.review.id IN :reviewIds
			""")
	List<Long> findRecommendedReviewIds(@Param("memberId") Long memberId, @Param("reviewIds") List<Long> reviewIds);

	boolean existsByMemberIdAndReviewId(Long memberId, Long reviewId);

	Optional<ReviewRecommend> findByMemberIdAndReviewId(Long memberId, Long reviewId);

	long deleteByReviewId(Long reviewId);
}
