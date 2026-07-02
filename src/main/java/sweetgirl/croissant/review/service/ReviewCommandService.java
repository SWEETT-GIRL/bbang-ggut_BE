package sweetgirl.croissant.review.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

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
import sweetgirl.croissant.review.dto.request.ReviewCreateRequest;
import sweetgirl.croissant.review.dto.request.ReviewImageRequest;
import sweetgirl.croissant.review.dto.request.ReviewPresignedUrlRequest;
import sweetgirl.croissant.review.dto.request.ReviewUpdateRequest;
import sweetgirl.croissant.review.dto.response.ReviewCreateResponse;
import sweetgirl.croissant.review.dto.response.ReviewDetailResponse;
import sweetgirl.croissant.review.dto.response.ReviewPresignedUrlInfo;
import sweetgirl.croissant.review.dto.response.ReviewPresignedUrlInfoResponse;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.entity.ReviewRecommend;
import sweetgirl.croissant.review.enums.TextureTag;
import sweetgirl.croissant.review.exception.ReviewException;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRecommendRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReviewCommandService {

	private static final int MAX_REVIEW_IMAGES = 5;
	private static final int MAX_CONTENT_LENGTH = 1000;
	private static final BigDecimal MIN_RATING = BigDecimal.valueOf(0.5);
	private static final BigDecimal MAX_RATING = BigDecimal.valueOf(5.0);
	private static final BigDecimal HALF_STEP_MULTIPLIER = BigDecimal.valueOf(2);
	private static final Set<String> ALLOWED_IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png");

	private final S3Service s3Service;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final MenuRepository menuRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final ReviewRecommendRepository reviewRecommendRepository;
	private final ObjectionableContentFilter objectionableContentFilter;
	private final ReviewResponseAssembler reviewResponseAssembler;

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

		return reviewResponseAssembler.toReviewDetailResponses(List.of(review), member.getId()).get(0);
	}

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

	public void cancelReviewRecommend(Long reviewId, Long memberId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));

		ReviewRecommend reviewRecommend = reviewRecommendRepository.findByMemberIdAndReviewId(memberId, reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.NOT_RECOMMENDED));

		reviewRecommendRepository.delete(reviewRecommend);
		reviewRepository.decrementRecommendCount(reviewId);
	}

	public void deleteReview(Long reviewId, Long memberId) {
		Member member = memberReader.getMember(memberId);
		Review review = getOwnedActiveReview(reviewId, member.getId());

		deleteReviewImages(review);
		reviewRecommendRepository.deleteByReviewId(review.getId());
		review.softDelete();
	}

	private Review getOwnedActiveReview(Long reviewId, Long memberId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));
		if (!review.getMember().getId().equals(memberId)) {
			throw new ReviewException(ReviewErrorCode.REVIEW_ACCESS_DENIED);
		}
		return review;
	}

	private void validateReviewEditable(Review review) {
		if (reviewResponseAssembler.isEditExpired(review)) {
			throw new ReviewException(ReviewErrorCode.REVIEW_EDIT_PERIOD_EXPIRED);
		}
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
}
