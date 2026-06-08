package sweetgirl.croissant.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.review.entity.ReviewLike;

public interface ReviewLikeRepository extends JpaRepository<ReviewLike, Long> {

	@Query("""
			SELECT rl.review.id FROM ReviewLike rl
			WHERE rl.member.id = :memberId AND rl.review.id IN :reviewIds
			""")
	List<Long> findLikedReviewIds(@Param("memberId") Long memberId, @Param("reviewIds") List<Long> reviewIds);

	boolean existsByMemberIdAndReviewId(Long memberId, Long reviewId);

	Optional<ReviewLike> findByMemberIdAndReviewId(Long memberId, Long reviewId);

	long deleteByReviewId(Long reviewId);
}
