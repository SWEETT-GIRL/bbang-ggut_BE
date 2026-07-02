package sweetgirl.croissant.review.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.dto.response.MyReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewDetailResponse;
import sweetgirl.croissant.review.dto.response.ReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewSummaryResponse;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.enums.ReviewSort;
import sweetgirl.croissant.review.exception.ReviewException;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewQueryService {

	private final MemberReader memberReader;
	private final MenuRepository menuRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewResponseAssembler reviewResponseAssembler;

	public ReviewSummaryResponse getReviewSummary(
			Long menuId,
			String sort,
			boolean photoOnly,
			int previewSize,
			Long memberId) {
		if (previewSize < 0) {
			throw new IllegalArgumentException("previewSize must be greater than or equal to 0");
		}
		validateMenuExists(menuId);

		List<Review> reviews = previewSize == 0
			? List.of()
			: findReviews(menuId, ReviewSort.from(sort), photoOnly, memberId, PageRequest.of(0, previewSize));

		return new ReviewSummaryResponse(
			menuId,
			calculateAverageRating(menuId, memberId),
			reviewRepository.countVisibleActiveByMenuId(menuId, memberId),
			reviewResponseAssembler.toReviewResponses(reviews, memberId));
	}

	public ReviewListResponse getReviews(
			Long menuId,
			String sort,
			boolean photoOnly,
			int page,
			int size,
			Long memberId) {
		if (page < 0 || size < 1) {
			throw new IllegalArgumentException("page and size must be valid");
		}
		validateMenuExists(menuId);

		List<Review> reviews = findReviews(menuId, ReviewSort.from(sort), photoOnly, memberId,
			PageRequest.of(page, size + 1));
		boolean hasNext = reviews.size() > size;
		List<Review> pageReviews = hasNext ? reviews.subList(0, size) : reviews;

		return new ReviewListResponse(
			menuId,
			calculateAverageRating(menuId, memberId),
			reviewRepository.countVisibleActiveByMenuId(menuId, memberId),
			reviewResponseAssembler.toReviewResponses(pageReviews, memberId),
			hasNext);
	}

	public ReviewDetailResponse getReviewDetail(Long reviewId, Long memberId) {
		Review review = reviewRepository.findVisibleActiveByIdWithDetails(reviewId, memberId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));

		return reviewResponseAssembler.toReviewDetailResponses(List.of(review), memberId).get(0);
	}

	public MyReviewListResponse getMyReviews(Long memberId, int page, int size) {
		if (page < 0 || size < 1) {
			throw new IllegalArgumentException("page and size must be valid");
		}
		memberReader.getMember(memberId);

		List<Review> reviews = reviewRepository.findActiveByMemberIdOrderByRecent(
			memberId,
			PageRequest.of(page, size + 1));
		boolean hasNext = reviews.size() > size;
		List<Review> pageReviews = hasNext ? reviews.subList(0, size) : reviews;

		return new MyReviewListResponse(reviewResponseAssembler.toReviewDetailResponses(pageReviews, memberId),
			hasNext);
	}

	public MyReviewListResponse getStoreReviews(Long storeId, Long viewerMemberId, int page, int size) {
		if (page < 0 || size < 1) {
			throw new IllegalArgumentException("page and size must be valid");
		}

		List<Review> reviews = reviewRepository.findVisibleActiveByStoreIdOrderByRecent(
			storeId,
			viewerMemberId,
			PageRequest.of(page, size + 1));
		boolean hasNext = reviews.size() > size;
		List<Review> pageReviews = hasNext ? reviews.subList(0, size) : reviews;

		return new MyReviewListResponse(
			reviewResponseAssembler.toReviewDetailResponses(pageReviews, viewerMemberId), hasNext);
	}

	public MyReviewListResponse getMemberReviews(Long memberId, Long viewerMemberId, int page, int size) {
		if (page < 0 || size < 1) {
			throw new IllegalArgumentException("page and size must be valid");
		}
		memberReader.getMember(memberId);

		List<Review> reviews = reviewRepository.findVisibleActiveByMemberIdOrderByRecent(
			memberId,
			viewerMemberId,
			PageRequest.of(page, size + 1));
		boolean hasNext = reviews.size() > size;
		List<Review> pageReviews = hasNext ? reviews.subList(0, size) : reviews;

		return new MyReviewListResponse(
			reviewResponseAssembler.toReviewDetailResponses(pageReviews, viewerMemberId), hasNext);
	}

	private List<Review> findReviews(
			Long menuId,
			ReviewSort sort,
			boolean photoOnly,
			Long viewerMemberId,
			PageRequest pageRequest) {
		if (sort == ReviewSort.RECOMMENDED) {
			return reviewRepository.findVisibleActiveReviewsOrderByRecommended(
				menuId,
				photoOnly,
				viewerMemberId,
				pageRequest);
		}
		return reviewRepository.findVisibleActiveReviewsOrderByRecent(
			menuId,
			photoOnly,
			viewerMemberId,
			pageRequest);
	}

	private void validateMenuExists(Long menuId) {
		if (menuRepository.findByIdWithDetails(menuId).isEmpty()) {
			throw new MenuException(MenuErrorCode.MENU_NOT_FOUND);
		}
	}

	private BigDecimal calculateAverageRating(Long menuId, Long viewerMemberId) {
		List<BigDecimal> ratings = reviewRepository.findVisibleActiveRatingsByMenuId(menuId, viewerMemberId);
		return calculateAverage(ratings);
	}

	private BigDecimal calculateAverage(List<BigDecimal> ratings) {
		if (ratings.isEmpty()) {
			return BigDecimal.ZERO.setScale(1);
		}
		BigDecimal sum = ratings.stream()
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return sum.divide(BigDecimal.valueOf(ratings.size()), 1, RoundingMode.HALF_UP);
	}
}
