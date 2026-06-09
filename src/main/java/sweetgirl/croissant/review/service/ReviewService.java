package sweetgirl.croissant.review.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.dto.projection.ReviewAuthorAggregateProjection;
import sweetgirl.croissant.review.dto.request.ReviewCreateRequest;
import sweetgirl.croissant.review.dto.request.ReviewImageRequest;
import sweetgirl.croissant.review.dto.request.ReviewPresignedUrlRequest;
import sweetgirl.croissant.review.dto.request.ReviewUpdateRequest;
import sweetgirl.croissant.review.dto.response.MenuReviewResponse;
import sweetgirl.croissant.review.dto.response.MyReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewAuthorResponse;
import sweetgirl.croissant.review.dto.response.ReviewCreateResponse;
import sweetgirl.croissant.review.dto.response.ReviewDetailResponse;
import sweetgirl.croissant.review.dto.response.ReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewPhotoResponse;
import sweetgirl.croissant.review.dto.response.ReviewPresignedUrlInfo;
import sweetgirl.croissant.review.dto.response.ReviewPresignedUrlInfoResponse;
import sweetgirl.croissant.review.dto.response.ReviewSummaryResponse;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.entity.ReviewRecommend;
import sweetgirl.croissant.review.enums.ReviewSort;
import sweetgirl.croissant.review.enums.TextureTag;
import sweetgirl.croissant.review.exception.ReviewException;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRecommendRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReviewService {

	private static final int MAX_REVIEW_IMAGES = 5;
	private static final int MAX_CONTENT_LENGTH = 1000;
	private static final BigDecimal MIN_RATING = BigDecimal.valueOf(0.5);
	private static final BigDecimal MAX_RATING = BigDecimal.valueOf(5.0);
	private static final BigDecimal HALF_STEP_MULTIPLIER = BigDecimal.valueOf(2);
	private static final long REVIEW_EDIT_LIMIT_DAYS = 1;
	private static final Set<String> ALLOWED_IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png");

	private final S3Service s3Service;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final MenuRepository menuRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final ReviewRecommendRepository reviewRecommendRepository;
	private final ObjectionableContentFilter objectionableContentFilter;

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
			toReviewResponses(reviews, memberId));
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
			toReviewResponses(pageReviews, memberId),
			hasNext);
	}

	public ReviewDetailResponse getReviewDetail(Long reviewId, Long memberId) {
		Review review = reviewRepository.findVisibleActiveByIdWithDetails(reviewId, memberId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));

		return toReviewDetailResponses(List.of(review), memberId).get(0);
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

		return new MyReviewListResponse(toReviewDetailResponses(pageReviews, memberId), hasNext);
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

		return new MyReviewListResponse(toReviewDetailResponses(pageReviews, viewerMemberId), hasNext);
	}

	public ReviewPresignedUrlInfoResponse generateReviewPresignedUrls(
			Long memberId,
			ReviewPresignedUrlRequest request) {
		memberReader.getMember(memberId);
		validatePresignedUrlRequest(request);

		List<ReviewPresignedUrlInfo> presignedUrls = request.mimeTypes().stream()
			.map(mimeType -> {
				PresignedUrlResponse response = s3Service.generatePresignedUrl(
					S3PathGenerator.getReviewImagePath(memberId),
					mimeType);
				return new ReviewPresignedUrlInfo(response.uploadUrl(), response.awsKey());
			})
			.toList();

		return new ReviewPresignedUrlInfoResponse(presignedUrls);
	}

	@Transactional
	public ReviewCreateResponse createReview(Long menuId, Long memberId, ReviewCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		Menu menu = menuRepository.findByIdWithDetails(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		BigDecimal rating = validateRating(request.rating());
		List<TextureTag> textureTags = validateTextureTags(request.textureTags());
		String content = normalizeContent(request.content());
		objectionableContentFilter.validate(content);
		List<ReviewImageRequest> images = validateReviewImages(memberId, request.images());

		Review review = reviewRepository.save(Review.builder()
			.menu(menu)
			.member(member)
			.rating(rating)
			.repurchaseIntent(request.repurchaseIntent())
			.sweetness(request.sweetness())
			.fillingAmount(request.fillingAmount())
			.textureTags(textureTags)
			.content(content)
			.build());

		if (!images.isEmpty()) {
			reviewImageFileRepository.saveAll(toImageFiles(review, images));
		}

		return new ReviewCreateResponse(review.getId(), review.getCreatedAt());
	}

	@Transactional
	public ReviewDetailResponse updateReview(Long reviewId, Long memberId, ReviewUpdateRequest request) {
		Member member = memberReader.getMember(memberId);
		Review review = getOwnedActiveReview(reviewId, member.getId());
		validateReviewEditable(review);

		BigDecimal rating = validateRating(request.rating());
		List<TextureTag> textureTags = validateTextureTags(request.textureTags());
		String content = normalizeContent(request.content());
		objectionableContentFilter.validate(content);

		review.update(
			rating,
			request.repurchaseIntent(),
			request.sweetness(),
			request.fillingAmount(),
			textureTags,
			content);

		if (request.images() != null) {
			replaceReviewImages(review, member.getId(), request.images());
		}

		return toReviewDetailResponses(List.of(review), member.getId()).get(0);
	}

	@Transactional
	public void recommendReview(Long reviewId, Long memberId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));

		if (reviewRecommendRepository.existsByMemberIdAndReviewId(memberId, reviewId)) {
			throw new ReviewException(ReviewErrorCode.ALREADY_RECOMMENDED);
		}

		reviewRecommendRepository.save(ReviewRecommend.builder()
			.review(review)
			.member(memberReader.getMember(memberId))
			.build());
		reviewRepository.incrementRecommendCount(reviewId);
	}

	@Transactional
	public void cancelReviewRecommend(Long reviewId, Long memberId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));

		ReviewRecommend reviewRecommend = reviewRecommendRepository.findByMemberIdAndReviewId(memberId, reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.NOT_RECOMMENDED));

		reviewRecommendRepository.delete(reviewRecommend);
		reviewRepository.decrementRecommendCount(reviewId);
	}

	@Transactional
	public void deleteReview(Long reviewId, Long memberId) {
		Member member = memberReader.getMember(memberId);
		Review review = getOwnedActiveReview(reviewId, member.getId());

		deleteReviewImages(review);
		reviewRecommendRepository.deleteByReviewId(review.getId());
		review.softDelete();
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

	private Review getOwnedActiveReview(Long reviewId, Long memberId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));
		if (!review.getMember().getId().equals(memberId)) {
			throw new ReviewException(ReviewErrorCode.REVIEW_ACCESS_DENIED);
		}
		return review;
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

	private List<MenuReviewResponse> toReviewResponses(List<Review> reviews, Long memberId) {
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

	private List<ReviewDetailResponse> toReviewDetailResponses(List<Review> reviews, Long memberId) {
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

	private Map<Long, List<ReviewPhotoResponse>> buildPhotoMap(List<Long> reviewIds) {
		return reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(reviewIds).stream()
			.collect(Collectors.groupingBy(
				image -> image.getReview().getId(),
				Collectors.mapping(
					image -> new ReviewPhotoResponse(image.getId(), image.getAwsKey()),
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

	private void validateReviewEditable(Review review) {
		if (isReviewEditExpired(review)) {
			throw new ReviewException(ReviewErrorCode.REVIEW_EDIT_PERIOD_EXPIRED);
		}
	}

	private boolean canEditReview(Review review, Long viewerMemberId) {
		return canDeleteReview(review, viewerMemberId) && !isReviewEditExpired(review);
	}

	private boolean canDeleteReview(Review review, Long viewerMemberId) {
		return viewerMemberId != null && review.getMember().getId().equals(viewerMemberId);
	}

	private boolean isReviewEditExpired(Review review) {
		LocalDateTime createdAt = review.getCreatedAt();
		return createdAt != null && createdAt.plusDays(REVIEW_EDIT_LIMIT_DAYS).isBefore(LocalDateTime.now());
	}

	private BigDecimal toAverageRating(Double value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(1);
		}
		return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
	}

	private void validatePresignedUrlRequest(ReviewPresignedUrlRequest request) {
		if (request.mimeTypes() == null || request.mimeTypes().isEmpty()) {
			throw new ReviewException(ReviewErrorCode.INVALID_REVIEW_IMAGE_MIME_TYPE);
		}
		if (request.mimeTypes().size() > MAX_REVIEW_IMAGES) {
			throw new ReviewException(ReviewErrorCode.REVIEW_IMAGE_COUNT_EXCEEDED);
		}
		request.mimeTypes().forEach(this::validateReviewMimeType);
	}

	private BigDecimal validateRating(BigDecimal rating) {
		if (rating == null
			|| rating.compareTo(MIN_RATING) < 0
			|| rating.compareTo(MAX_RATING) > 0
			|| rating.multiply(HALF_STEP_MULTIPLIER).remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
			throw new ReviewException(ReviewErrorCode.INVALID_RATING);
		}
		return rating.setScale(1, RoundingMode.UNNECESSARY);
	}

	private List<TextureTag> validateTextureTags(List<TextureTag> textureTags) {
		if (textureTags == null || textureTags.isEmpty() || textureTags.size() > 5) {
			throw new ReviewException(ReviewErrorCode.INVALID_TEXTURE_TAGS);
		}
		return textureTags;
	}

	private String normalizeContent(String content) {
		String normalized = content == null ? "" : content.trim();
		if (normalized.length() > MAX_CONTENT_LENGTH) {
			throw new ReviewException(ReviewErrorCode.REVIEW_CONTENT_TOO_LONG);
		}
		return normalized;
	}

	private List<ReviewImageRequest> validateReviewImages(Long memberId, List<ReviewImageRequest> images) {
		if (images == null) {
			return List.of();
		}
		if (images.size() > MAX_REVIEW_IMAGES) {
			throw new ReviewException(ReviewErrorCode.REVIEW_IMAGE_COUNT_EXCEEDED);
		}

		String expectedPrefix = S3PathGenerator.getReviewImagePath(memberId) + "/";
		for (ReviewImageRequest image : images) {
			validateReviewMimeType(image.contentType());
			if (!image.awsKey().startsWith(expectedPrefix)) {
				throw new ReviewException(ReviewErrorCode.INVALID_REVIEW_IMAGE_KEY);
			}
		}
		return images;
	}

	private void validateReviewMimeType(String mimeType) {
		if (mimeType == null || !ALLOWED_IMAGE_MIME_TYPES.contains(mimeType.toLowerCase(Locale.ROOT))) {
			throw new ReviewException(ReviewErrorCode.INVALID_REVIEW_IMAGE_MIME_TYPE);
		}
	}

	private List<ReviewImageFile> toImageFiles(Review review, List<ReviewImageRequest> images) {
		return IntStream.range(0, images.size())
			.mapToObj(index -> {
				ReviewImageRequest image = images.get(index);
				return ReviewImageFile.builder()
					.review(review)
					.name(image.fileName())
					.mimeType(MimeType.fromString(image.contentType()))
					.awsKey(image.awsKey())
					.displayOrder(index)
					.build();
			})
			.toList();
	}

	private void replaceReviewImages(Review review, Long memberId, List<ReviewImageRequest> requestImages) {
		List<ReviewImageRequest> images = validateReviewImages(memberId, requestImages);
		deleteReviewImages(review);
		if (!images.isEmpty()) {
			reviewImageFileRepository.saveAll(toImageFiles(review, images));
		}
	}

	private void deleteReviewImages(Review review) {
		List<ReviewImageFile> images = reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(
			review.getId());
		images.forEach(image -> safeDelete(image.getAwsKey()));
		reviewImageFileRepository.deleteAll(images);
	}

	private void safeDelete(String awsKey) {
		try {
			s3Service.deleteObject(awsKey);
		} catch (RuntimeException e) {
			log.warn("리뷰 이미지 S3 삭제 실패 [key={}]: {}", awsKey, e.getMessage());
		}
	}

	private record AuthorStats(Long reviewCount, BigDecimal averageRating) {
		private static AuthorStats empty() {
			return new AuthorStats(0L, BigDecimal.ZERO.setScale(1));
		}
	}
}
