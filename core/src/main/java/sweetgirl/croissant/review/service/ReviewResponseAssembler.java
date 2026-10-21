package sweetgirl.croissant.review.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.review.dto.projection.ReviewAuthorAggregateProjection;
import sweetgirl.croissant.review.dto.response.MenuReviewResponse;
import sweetgirl.croissant.review.dto.response.ReviewAuthorResponse;
import sweetgirl.croissant.review.dto.response.ReviewDetailResponse;
import sweetgirl.croissant.review.dto.response.ReviewPhotoResponse;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRecommendRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;

// 리뷰 목록/상세 응답 조립 공통 로직 — Query/Command 서비스가 공유한다.
@Component
@RequiredArgsConstructor
public class ReviewResponseAssembler {

	private static final long REVIEW_EDIT_LIMIT_DAYS = 1;

	private final ReviewRepository reviewRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final ReviewRecommendRepository reviewRecommendRepository;

	public List<MenuReviewResponse> toReviewResponses(List<Review> reviews, Long memberId) {
		if (reviews.isEmpty()) {
			return List.of();
		}

		List<Long> reviewIds = reviews.stream()
			.map(Review::getId)
			.toList();
		Map<Long, List<ReviewPhotoResponse>> photoMap = buildPhotoMap(reviewIds);
		Set<Long> recommendedReviewIds = findRecommendedReviewIds(memberId, reviewIds);
		Map<Long, AuthorStats> authorStatsMap = buildAuthorStatsMap(reviews);

		return reviews.stream()
			.map(review -> toReviewResponse(review, memberId, photoMap, recommendedReviewIds, authorStatsMap))
			.toList();
	}

	public List<ReviewDetailResponse> toReviewDetailResponses(List<Review> reviews, Long memberId) {
		if (reviews.isEmpty()) {
			return List.of();
		}

		List<Long> reviewIds = reviews.stream()
			.map(Review::getId)
			.toList();
		Map<Long, List<ReviewPhotoResponse>> photoMap = buildPhotoMap(reviewIds);
		Set<Long> recommendedReviewIds = findRecommendedReviewIds(memberId, reviewIds);
		Map<Long, AuthorStats> authorStatsMap = buildAuthorStatsMap(reviews);

		return reviews.stream()
			.map(review -> toReviewDetailResponse(review, memberId, photoMap, recommendedReviewIds, authorStatsMap))
			.toList();
	}

	// 리뷰 수정 가능 기간이 지났는지 여부 — 응답의 canEdit 플래그와 Command 수정 검증에서 공유한다.
	public boolean isEditExpired(Review review) {
		LocalDateTime createdAt = review.getCreatedAt();
		return createdAt != null && createdAt.plusDays(REVIEW_EDIT_LIMIT_DAYS).isBefore(LocalDateTime.now());
	}

	private Map<Long, List<ReviewPhotoResponse>> buildPhotoMap(List<Long> reviewIds) {
		return reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(reviewIds).stream()
			.collect(Collectors.groupingBy(
				image -> image.getReview().getId(),
				Collectors.mapping(
					image -> new ReviewPhotoResponse(image.getId(), image.getAwsKey(), image.getResizedKey640()),
					Collectors.toList())));
	}

	private Set<Long> findRecommendedReviewIds(Long memberId, List<Long> reviewIds) {
		if (memberId == null) {
			return Collections.emptySet();
		}
		return new HashSet<>(reviewRecommendRepository.findRecommendedReviewIds(memberId, reviewIds));
	}

	private Map<Long, AuthorStats> buildAuthorStatsMap(List<Review> reviews) {
		Set<Long> memberIds = reviews.stream()
			.map(review -> review.getMember().getId())
			.collect(Collectors.toSet());
		return reviewRepository.findAuthorAggregatesByMemberIds(memberIds).stream()
			.collect(Collectors.toMap(
				ReviewAuthorAggregateProjection::getMemberId,
				projection -> new AuthorStats(
					projection.getReviewCount(),
					toAverageRating(projection.getAverageRating()))));
	}

	private MenuReviewResponse toReviewResponse(
			Review review,
			Long viewerMemberId,
			Map<Long, List<ReviewPhotoResponse>> photoMap,
			Set<Long> recommendedReviewIds,
			Map<Long, AuthorStats> authorStatsMap) {
		Long memberId = review.getMember().getId();
		AuthorStats authorStats = authorStatsMap.getOrDefault(memberId, AuthorStats.empty());

		return new MenuReviewResponse(
			review.getId(),
			review.getRating(),
			review.getRepurchaseIntent(),
			review.getSweetness(),
			review.getFillingAmount(),
			review.getTextureTags(),
			review.getContent(),
			review.getRecommendCount(),
			review.getCommentCount(),
			recommendedReviewIds.contains(review.getId()),
			canEditReview(review, viewerMemberId),
			canDeleteReview(review, viewerMemberId),
			new ReviewAuthorResponse(
				memberId,
				review.getMember().getNickname(),
				authorStats.reviewCount(),
				authorStats.averageRating(),
				null,
				null),
			photoMap.getOrDefault(review.getId(), List.of()),
			review.getCreatedAt());
	}

	private ReviewDetailResponse toReviewDetailResponse(
			Review review,
			Long viewerMemberId,
			Map<Long, List<ReviewPhotoResponse>> photoMap,
			Set<Long> recommendedReviewIds,
			Map<Long, AuthorStats> authorStatsMap) {
		Long memberId = review.getMember().getId();
		AuthorStats authorStats = authorStatsMap.getOrDefault(memberId, AuthorStats.empty());

		return new ReviewDetailResponse(
			review.getId(),
			review.getMenu().getId(),
			review.getMenu().getName(),
			review.getMenu().getStore().getId(),
			review.getMenu().getStore().getName(),
			review.getRating(),
			review.getRepurchaseIntent(),
			review.getSweetness(),
			review.getFillingAmount(),
			review.getTextureTags(),
			review.getContent(),
			review.getRecommendCount(),
			review.getCommentCount(),
			recommendedReviewIds.contains(review.getId()),
			canEditReview(review, viewerMemberId),
			canDeleteReview(review, viewerMemberId),
			new ReviewAuthorResponse(
				memberId,
				review.getMember().getNickname(),
				authorStats.reviewCount(),
				authorStats.averageRating(),
				null,
				null),
			photoMap.getOrDefault(review.getId(), List.of()),
			review.getCreatedAt(),
			review.getUpdatedAt());
	}

	private boolean canEditReview(Review review, Long viewerMemberId) {
		return canDeleteReview(review, viewerMemberId) && !isEditExpired(review);
	}

	private boolean canDeleteReview(Review review, Long viewerMemberId) {
		return viewerMemberId != null && review.getMember().getId().equals(viewerMemberId);
	}

	private BigDecimal toAverageRating(Double value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(1);
		}
		return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
	}

	private record AuthorStats(Long reviewCount, BigDecimal averageRating) {
		private static AuthorStats empty() {
			return new AuthorStats(0L, BigDecimal.ZERO.setScale(1));
		}
	}
}
