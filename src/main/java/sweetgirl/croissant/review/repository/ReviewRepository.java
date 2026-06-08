package sweetgirl.croissant.review.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.review.dto.projection.MenuReviewCountProjection;
import sweetgirl.croissant.review.dto.projection.ReviewAuthorAggregateProjection;
import sweetgirl.croissant.review.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			JOIN FETCH r.menu m
			JOIN FETCH m.store
			WHERE r.id = :reviewId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
			""")
	Optional<Review> findActiveByIdWithDetails(@Param("reviewId") Long reviewId);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			JOIN FETCH r.menu m
			JOIN FETCH m.store
			WHERE r.id = :reviewId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			""")
	Optional<Review> findVisibleActiveByIdWithDetails(
			@Param("reviewId") Long reviewId,
			@Param("viewerMemberId") Long viewerMemberId);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			JOIN FETCH r.menu m
			JOIN FETCH m.store
			WHERE r.id = :reviewId
			""")
	Optional<Review> findByIdWithDetails(@Param("reviewId") Long reviewId);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			JOIN FETCH r.menu m
			JOIN FETCH m.store
			WHERE r.member.id = :memberId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
			ORDER BY r.createdAt DESC, r.id DESC
			""")
	List<Review> findActiveByMemberIdOrderByRecent(
			@Param("memberId") Long memberId,
			Pageable pageable);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			JOIN FETCH r.menu m
			JOIN FETCH m.store
			WHERE r.member.id = :memberId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			ORDER BY r.createdAt DESC, r.id DESC
			""")
	List<Review> findVisibleActiveByMemberIdOrderByRecent(
			@Param("memberId") Long memberId,
			@Param("viewerMemberId") Long viewerMemberId,
			Pageable pageable);

	@Query("""
			SELECT COUNT(r) FROM Review r
			WHERE r.menu.id = :menuId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			""")
	long countVisibleActiveByMenuId(
			@Param("menuId") Long menuId,
			@Param("viewerMemberId") Long viewerMemberId);

	@Query("""
			SELECT r.menu.id AS menuId,
				COUNT(r) AS reviewCount
			FROM Review r
			WHERE r.menu.id IN :menuIds
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			GROUP BY r.menu.id
			""")
	List<MenuReviewCountProjection> countVisibleActiveByMenuIds(
			@Param("menuIds") List<Long> menuIds,
			@Param("viewerMemberId") Long viewerMemberId);

	@Query("""
			SELECT r.rating FROM Review r
			WHERE r.menu.id = :menuId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			""")
	List<BigDecimal> findVisibleActiveRatingsByMenuId(
			@Param("menuId") Long menuId,
			@Param("viewerMemberId") Long viewerMemberId);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			WHERE r.menu.id = :menuId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:photoOnly = false OR EXISTS (
					SELECT 1 FROM ReviewImageFile image
					WHERE image.review = r
				))
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			ORDER BY r.createdAt DESC, r.id DESC
			""")
	List<Review> findVisibleActiveReviewsOrderByRecent(
			@Param("menuId") Long menuId,
			@Param("photoOnly") boolean photoOnly,
			@Param("viewerMemberId") Long viewerMemberId,
			Pageable pageable);

	@Query("""
			SELECT r FROM Review r
			JOIN FETCH r.member
			WHERE r.menu.id = :menuId
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
				AND (:photoOnly = false OR EXISTS (
					SELECT 1 FROM ReviewImageFile image
					WHERE image.review = r
				))
				AND (:viewerMemberId IS NULL OR NOT EXISTS (
					SELECT 1 FROM MemberBlock mb
					WHERE mb.blocker.id = :viewerMemberId
						AND mb.blockedMember.id = r.member.id
				))
			ORDER BY r.recommendCount DESC, r.createdAt DESC, r.id DESC
			""")
	List<Review> findVisibleActiveReviewsOrderByRecommended(
			@Param("menuId") Long menuId,
			@Param("photoOnly") boolean photoOnly,
			@Param("viewerMemberId") Long viewerMemberId,
			Pageable pageable);

	@Modifying
	@Query("UPDATE Review r SET r.recommendCount = r.recommendCount + 1 WHERE r.id = :reviewId")
	void incrementRecommendCount(@Param("reviewId") Long reviewId);

	@Modifying
	@Query("""
			UPDATE Review r
			SET r.recommendCount = CASE WHEN r.recommendCount > 0 THEN r.recommendCount - 1 ELSE 0 END
			WHERE r.id = :reviewId
			""")
	void decrementRecommendCount(@Param("reviewId") Long reviewId);

	@Query("""
			SELECT r.member.id AS memberId,
				COUNT(r) AS reviewCount,
				AVG(r.rating) AS averageRating
			FROM Review r
			WHERE r.member.id IN :memberIds
				AND r.deletedAt IS NULL
				AND r.hiddenAt IS NULL
			GROUP BY r.member.id
			""")
	List<ReviewAuthorAggregateProjection> findAuthorAggregatesByMemberIds(@Param("memberIds") Set<Long> memberIds);
}
