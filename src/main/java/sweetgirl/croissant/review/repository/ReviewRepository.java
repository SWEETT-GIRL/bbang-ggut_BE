package sweetgirl.croissant.review.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.review.dto.projection.ReviewAuthorAggregateProjection;
import sweetgirl.croissant.review.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

	boolean existsByMemberIdAndMenuIdAndDeletedAtIsNull(Long memberId, Long menuId);

	@Query("""
			SELECT COUNT(r) FROM Review r
			WHERE r.menu.id = :menuId AND r.deletedAt IS NULL
			""")
	long countActiveByMenuId(@Param("menuId") Long menuId);

	@Query("""
			SELECT r.rating FROM Review r
			WHERE r.menu.id = :menuId AND r.deletedAt IS NULL
			""")
	List<BigDecimal> findActiveRatingsByMenuId(@Param("menuId") Long menuId);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			WHERE r.menu.id = :menuId
				AND r.deletedAt IS NULL
				AND (:photoOnly = false OR EXISTS (
					SELECT 1 FROM ReviewImageFile image
					WHERE image.review = r
				))
			ORDER BY r.createdAt DESC, r.id DESC
			""")
	List<Review> findActiveReviewsOrderByRecent(
			@Param("menuId") Long menuId,
			@Param("photoOnly") boolean photoOnly,
			Pageable pageable);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			WHERE r.menu.id = :menuId
				AND r.deletedAt IS NULL
				AND (:photoOnly = false OR EXISTS (
					SELECT 1 FROM ReviewImageFile image
					WHERE image.review = r
				))
			ORDER BY r.likeCount DESC, r.createdAt DESC, r.id DESC
			""")
	List<Review> findActiveReviewsOrderByLiked(
			@Param("menuId") Long menuId,
			@Param("photoOnly") boolean photoOnly,
			Pageable pageable);

	@Query("""
			SELECT r.member.id AS memberId,
				COUNT(r) AS reviewCount,
				AVG(r.rating) AS averageRating
			FROM Review r
			WHERE r.member.id IN :memberIds AND r.deletedAt IS NULL
			GROUP BY r.member.id
			""")
	List<ReviewAuthorAggregateProjection> findAuthorAggregatesByMemberIds(@Param("memberIds") Set<Long> memberIds);
}
