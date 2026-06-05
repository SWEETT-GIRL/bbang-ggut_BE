package sweetgirl.croissant.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.dto.projection.ReviewAuthorAggregateProjection;
import sweetgirl.croissant.review.dto.request.ReviewCreateRequest;
import sweetgirl.croissant.review.dto.request.ReviewImageRequest;
import sweetgirl.croissant.review.dto.request.ReviewPresignedUrlRequest;
import sweetgirl.croissant.review.dto.request.ReviewUpdateRequest;
import sweetgirl.croissant.review.dto.response.ReviewCreateResponse;
import sweetgirl.croissant.review.dto.response.ReviewDetailResponse;
import sweetgirl.croissant.review.dto.response.ReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewPresignedUrlInfoResponse;
import sweetgirl.croissant.review.dto.response.ReviewSummaryResponse;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.enums.TextureTag;
import sweetgirl.croissant.review.exception.ReviewException;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewLikeRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

	@Mock
	S3Service s3Service;
	@Mock
	MemberReader memberReader;
	@Mock
	MemberRestrictionReader memberRestrictionReader;
	@Mock
	MenuRepository menuRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	ReviewImageFileRepository reviewImageFileRepository;
	@Mock
	ReviewLikeRepository reviewLikeRepository;
	@Mock
	ObjectionableContentFilter objectionableContentFilter;

	@InjectMocks
	ReviewService reviewService;

	@Test
	@DisplayName("getReviewSummary: liked 정렬 기본 조회와 로그인 사용자의 isLiked를 반영한다")
	void getReviewSummary_likedSort_returnsPreviewWithLikedState() {
		Member author = member(10L, "단팥");
		Review review = review(1L, author, BigDecimal.valueOf(4.5), 7, LocalDateTime.of(2026, 6, 4, 10, 0));
		ReviewImageFile image = image(100L, review, "reviews/members/10/images/a");

		given(menuRepository.findByIdWithDetails(1L)).willReturn(Optional.of(mock(Menu.class)));
		given(reviewRepository.findVisibleActiveReviewsOrderByLiked(
			eq(1L),
			eq(false),
			eq(99L),
			any(Pageable.class)))
			.willReturn(List.of(review));
		given(reviewRepository.findVisibleActiveRatingsByMenuId(1L, 99L))
			.willReturn(List.of(BigDecimal.valueOf(4.0), BigDecimal.valueOf(5.0)));
		given(reviewRepository.countVisibleActiveByMenuId(1L, 99L)).willReturn(2L);
		given(reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List.of(1L)))
			.willReturn(List.of(image));
		given(reviewLikeRepository.findLikedReviewIds(99L, List.of(1L))).willReturn(List.of(1L));
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(10L)))
			.willReturn(List.of(authorAggregate(10L, 3L, 4.0)));

		ReviewSummaryResponse result = reviewService.getReviewSummary(1L, "liked", false, 2, 99L);

		assertThat(result.averageRating()).isEqualByComparingTo("4.5");
		assertThat(result.reviewCount()).isEqualTo(2L);
		assertThat(result.previewReviews()).hasSize(1);
		assertThat(result.previewReviews().get(0).isLiked()).isTrue();
		assertThat(result.previewReviews().get(0).photos().get(0).awsKey())
			.isEqualTo("reviews/members/10/images/a");
		assertThat(result.previewReviews().get(0).author().reviewCount()).isEqualTo(3L);
	}

	@Test
	@DisplayName("getReviews: recent 정렬은 size+1 조회로 hasNext를 계산한다")
	void getReviews_recentSort_usesSizePlusOneAndHasNext() {
		Member author = member(10L, "단팥");
		List<Review> reviews = List.of(
			review(1L, author, BigDecimal.valueOf(5.0), 0, LocalDateTime.now()),
			review(2L, author, BigDecimal.valueOf(4.0), 0, LocalDateTime.now()),
			review(3L, author, BigDecimal.valueOf(3.0), 0, LocalDateTime.now()));

		given(menuRepository.findByIdWithDetails(1L)).willReturn(Optional.of(mock(Menu.class)));
		given(reviewRepository.findVisibleActiveReviewsOrderByRecent(
			eq(1L),
			eq(true),
			isNull(),
			any(Pageable.class)))
			.willReturn(reviews);
		given(reviewRepository.findVisibleActiveRatingsByMenuId(1L, null))
			.willReturn(List.of(BigDecimal.valueOf(4.0)));
		given(reviewRepository.countVisibleActiveByMenuId(1L, null)).willReturn(3L);
		given(reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List.of(1L, 2L)))
			.willReturn(List.of());
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(10L)))
			.willReturn(List.of(authorAggregate(10L, 3L, 4.0)));

		ReviewListResponse result = reviewService.getReviews(1L, "recent", true, 1, 2, null);

		assertThat(result.content()).hasSize(2);
		assertThat(result.hasNext()).isTrue();
		verify(reviewRepository).findVisibleActiveReviewsOrderByRecent(
			eq(1L),
			eq(true),
			isNull(),
			argThat(pageable -> pageable.getPageNumber() == 1 && pageable.getPageSize() == 3));
		verify(reviewLikeRepository, never()).findLikedReviewIds(any(), any());
	}

	@Test
	@DisplayName("getReviewDetail: 차단한 작성자의 리뷰는 REVIEW_NOT_FOUND로 응답한다")
	void getReviewDetail_blockedAuthor_throwsReviewNotFound() {
		given(reviewRepository.findVisibleActiveByIdWithDetails(1L, 99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> reviewService.getReviewDetail(1L, 99L))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("createReview: 정상 요청이면 리뷰와 이미지를 저장한다")
	void createReview_validRequest_savesReviewAndImages() {
		Member member = member(7L, "슈크림");
		ReviewCreateRequest request = createRequest(
			BigDecimal.valueOf(4.5),
			"  다시 살래요  ",
			List.of(new ReviewImageRequest("a.jpg", "image/jpeg", "reviews/members/7/images/a")));

		given(memberReader.getMember(7L)).willReturn(member);
		given(menuRepository.findByIdWithDetails(1L)).willReturn(Optional.of(mock(Menu.class)));
		given(reviewRepository.save(any())).willAnswer(invocation -> {
			Review review = invocation.getArgument(0);
			ReflectionTestUtils.setField(review, "id", 11L);
			ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.of(2026, 6, 4, 11, 0));
			return review;
		});

		ReviewCreateResponse result = reviewService.createReview(1L, 7L, request);

		assertThat(result.reviewId()).isEqualTo(11L);
		verify(objectionableContentFilter).validate("다시 살래요");
		verify(reviewImageFileRepository).saveAll(argThat(images -> {
			List<ReviewImageFile> imageList = StreamSupport.stream(images.spliterator(), false).toList();
			return imageList.size() == 1 && imageList.get(0).getDisplayOrder() == 0;
		}));
	}

	@Test
	@DisplayName("createReview: 부적절하거나 개인정보로 보이는 내용이면 저장하지 않는다")
	void createReview_rejectedContent_doesNotSaveReview() {
		ReviewCreateRequest request = createRequest(
			BigDecimal.valueOf(4.5),
			"010-1234-5678",
			List.of(new ReviewImageRequest("a.jpg", "image/jpeg", "reviews/members/7/images/a")));

		given(memberReader.getMember(7L)).willReturn(member(7L, "슈크림"));
		given(menuRepository.findByIdWithDetails(1L)).willReturn(Optional.of(mock(Menu.class)));
		willThrow(new ReviewException(ReviewErrorCode.REVIEW_CONTENT_REJECTED))
			.given(objectionableContentFilter).validate("010-1234-5678");

		assertThatThrownBy(() -> reviewService.createReview(1L, 7L, request))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_CONTENT_REJECTED.getMessage());

		verify(reviewRepository, never()).save(any());
		verify(reviewImageFileRepository, never()).saveAll(any());
	}

	@Test
	@DisplayName("createReview: 작성 제한 상태이면 리뷰를 생성할 수 없다")
	void createReview_writeRestrictedMember_throwsForbidden() {
		given(memberReader.getMember(7L)).willReturn(member(7L, "슈크림"));
		willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
			.given(memberRestrictionReader).validateCanWrite(7L);

		assertThatThrownBy(
			() -> reviewService.createReview(1L, 7L, createRequest(BigDecimal.valueOf(4.5), "", List.of())))
			.isInstanceOf(MemberException.class)
			.hasMessageContaining(MemberErrorCode.MEMBER_WRITE_RESTRICTED.getMessage());

		verify(menuRepository, never()).findByIdWithDetails(any());
		verify(reviewRepository, never()).save(any());
	}

	@Test
	@DisplayName("createReview: 0.5 단위가 아닌 별점이면 INVALID_RATING을 던진다")
	void createReview_invalidRating_throwsInvalidRating() {
		given(memberReader.getMember(7L)).willReturn(member(7L, "슈크림"));
		given(menuRepository.findByIdWithDetails(1L)).willReturn(Optional.of(mock(Menu.class)));

		assertThatThrownBy(
			() -> reviewService.createReview(1L, 7L, createRequest(BigDecimal.valueOf(4.3), "", List.of())))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.INVALID_RATING.getMessage());
	}

	@Test
	@DisplayName("generateReviewPresignedUrls: JPEG/PNG만 허용한다")
	void generateReviewPresignedUrls_allowsOnlyJpegAndPng() {
		given(memberReader.getMember(7L)).willReturn(member(7L, "슈크림"));
		given(s3Service.generatePresignedUrl("reviews/members/7/images", "image/jpeg"))
			.willReturn(new PresignedUrlResponse("a", "image/jpeg", "https://upload", "reviews/members/7/images/a"));

		ReviewPresignedUrlInfoResponse result = reviewService.generateReviewPresignedUrls(
			7L,
			new ReviewPresignedUrlRequest(List.of("image/jpeg")));

		assertThat(result.presignedUrls()).hasSize(1);
		assertThat(result.presignedUrls().get(0).awsKey()).isEqualTo("reviews/members/7/images/a");

		assertThatThrownBy(
			() -> reviewService.generateReviewPresignedUrls(7L, new ReviewPresignedUrlRequest(List.of("image/webp"))))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.INVALID_REVIEW_IMAGE_MIME_TYPE.getMessage());
	}

	@Test
	@DisplayName("getMyReviews: 내 활성 리뷰를 최신순 slice로 반환한다")
	void getMyReviews_returnsMyActiveReviews() {
		Member member = member(7L, "슈크림");
		Review review = reviewWithMenu(1L, member, BigDecimal.valueOf(4.5), 0, LocalDateTime.now());

		given(memberReader.getMember(7L)).willReturn(member);
		given(reviewRepository.findActiveByMemberIdOrderByRecent(eq(7L), any(Pageable.class)))
			.willReturn(List.of(review));
		given(reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List.of(1L)))
			.willReturn(List.of());
		given(reviewLikeRepository.findLikedReviewIds(7L, List.of(1L))).willReturn(List.of());
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(7L)))
			.willReturn(List.of(authorAggregate(7L, 1L, 4.5)));

		var result = reviewService.getMyReviews(7L, 0, 20);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).storeName()).isEqualTo("단팥당");
		assertThat(result.content().get(0).canEdit()).isTrue();
		assertThat(result.content().get(0).canDelete()).isTrue();
		assertThat(result.hasNext()).isFalse();
	}

	@Test
	@DisplayName("updateReview: 작성자 본인만 리뷰와 이미지를 교체할 수 있다")
	void updateReview_owner_replacesReviewAndImages() {
		Member member = member(7L, "슈크림");
		Review review = reviewWithMenu(1L, member, BigDecimal.valueOf(4.5), 0, LocalDateTime.now());
		ReviewImageFile oldImage = image(100L, review, "reviews/members/7/images/old");
		ReviewUpdateRequest request = updateRequest(List.of(
			new ReviewImageRequest("new.jpg", "image/jpeg", "reviews/members/7/images/new")));

		given(memberReader.getMember(7L)).willReturn(member);
		given(reviewRepository.findActiveByIdWithDetails(1L)).willReturn(Optional.of(review));
		given(reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(1L))
			.willReturn(List.of(oldImage));
		given(reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List.of(1L)))
			.willReturn(List.of());
		given(reviewLikeRepository.findLikedReviewIds(7L, List.of(1L))).willReturn(List.of());
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(7L)))
			.willReturn(List.of(authorAggregate(7L, 1L, 3.5)));

		ReviewDetailResponse result = reviewService.updateReview(1L, 7L, request);

		assertThat(result.rating()).isEqualByComparingTo("3.5");
		assertThat(result.textureTags()).containsExactly(TextureTag.CHEWY);
		assertThat(result.content()).isEqualTo("수정했어요");
		assertThat(result.canEdit()).isTrue();
		assertThat(result.canDelete()).isTrue();
		verify(objectionableContentFilter).validate("수정했어요");
		verify(s3Service).deleteObject("reviews/members/7/images/old");
		verify(reviewImageFileRepository).deleteAll(List.of(oldImage));
		verify(reviewImageFileRepository).saveAll(argThat(images -> {
			List<ReviewImageFile> imageList = StreamSupport.stream(images.spliterator(), false).toList();
			return imageList.size() == 1
				&& imageList.get(0).getAwsKey().equals("reviews/members/7/images/new");
		}));
	}

	@Test
	@DisplayName("updateReview: 부적절하거나 개인정보로 보이는 내용이면 리뷰와 이미지를 변경하지 않는다")
	void updateReview_rejectedContent_doesNotUpdateReview() {
		Member member = member(7L, "슈크림");
		Review review = reviewWithMenu(1L, member, BigDecimal.valueOf(4.5), 0, LocalDateTime.now());
		ReviewUpdateRequest request = updateRequest(
			"owner@example.com",
			List.of(new ReviewImageRequest("new.jpg", "image/jpeg", "reviews/members/7/images/new")));

		given(memberReader.getMember(7L)).willReturn(member);
		given(reviewRepository.findActiveByIdWithDetails(1L)).willReturn(Optional.of(review));
		willThrow(new ReviewException(ReviewErrorCode.REVIEW_CONTENT_REJECTED))
			.given(objectionableContentFilter).validate("owner@example.com");

		assertThatThrownBy(() -> reviewService.updateReview(1L, 7L, request))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_CONTENT_REJECTED.getMessage());

		assertThat(review.getContent()).isEqualTo("맛있어요");
		verify(reviewImageFileRepository, never()).findByReviewIdOrderByDisplayOrderAscIdAsc(any());
		verify(reviewImageFileRepository, never()).saveAll(any());
		verify(s3Service, never()).deleteObject(any());
	}

	@Test
	@DisplayName("updateReview: 작성 후 24시간이 지난 리뷰는 수정할 수 없다")
	void updateReview_expiredEditPeriod_throwsEditPeriodExpired() {
		Member member = member(7L, "슈크림");
		Review review = reviewWithMenu(1L, member, BigDecimal.valueOf(4.5), 0, LocalDateTime.now().minusDays(2));

		given(memberReader.getMember(7L)).willReturn(member);
		given(reviewRepository.findActiveByIdWithDetails(1L)).willReturn(Optional.of(review));

		assertThatThrownBy(() -> reviewService.updateReview(1L, 7L, updateRequest(List.of())))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_EDIT_PERIOD_EXPIRED.getMessage());

		assertThat(review.getContent()).isEqualTo("맛있어요");
		verify(objectionableContentFilter, never()).validate(any());
		verify(reviewImageFileRepository, never()).findByReviewIdOrderByDisplayOrderAscIdAsc(any());
		verify(reviewImageFileRepository, never()).saveAll(any());
		verify(s3Service, never()).deleteObject(any());
	}

	@Test
	@DisplayName("updateReview: 타인의 리뷰는 수정할 수 없다")
	void updateReview_otherMember_throwsAccessDenied() {
		Member owner = member(8L, "단팥");
		Review review = reviewWithMenu(1L, owner, BigDecimal.valueOf(4.5), 0, LocalDateTime.now());

		given(memberReader.getMember(7L)).willReturn(member(7L, "슈크림"));
		given(reviewRepository.findActiveByIdWithDetails(1L)).willReturn(Optional.of(review));

		assertThatThrownBy(() -> reviewService.updateReview(1L, 7L, updateRequest(List.of())))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("deleteReview: 작성자 본인은 이미지와 좋아요를 정리하고 리뷰를 soft delete한다")
	void deleteReview_owner_softDeletesAndCleansUp() {
		Member member = member(7L, "슈크림");
		Review review = reviewWithMenu(1L, member, BigDecimal.valueOf(4.5), 0, LocalDateTime.now());
		ReviewImageFile image = image(100L, review, "reviews/members/7/images/a");

		given(memberReader.getMember(7L)).willReturn(member);
		given(reviewRepository.findActiveByIdWithDetails(1L)).willReturn(Optional.of(review));
		given(reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(1L))
			.willReturn(List.of(image));

		reviewService.deleteReview(1L, 7L);

		assertThat(review.getDeletedAt()).isNotNull();
		verify(s3Service).deleteObject("reviews/members/7/images/a");
		verify(reviewImageFileRepository).deleteAll(List.of(image));
		verify(reviewLikeRepository).deleteByReviewId(1L);
	}

	private ReviewCreateRequest createRequest(BigDecimal rating, String content, List<ReviewImageRequest> images) {
		return new ReviewCreateRequest(
			rating,
			"yes",
			"medium",
			"full",
			List.of(TextureTag.CRISPY),
			content,
			images);
	}

	private ReviewUpdateRequest updateRequest(List<ReviewImageRequest> images) {
		return updateRequest("수정했어요", images);
	}

	private ReviewUpdateRequest updateRequest(String content, List<ReviewImageRequest> images) {
		return new ReviewUpdateRequest(
			BigDecimal.valueOf(3.5),
			"yes",
			"medium",
			"full",
			List.of(TextureTag.CHEWY),
			content,
			images);
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder()
			.nickname(nickname)
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Review review(Long id, Member member, BigDecimal rating, int likeCount, LocalDateTime createdAt) {
		Review review = Review.builder()
			.menu(mock(Menu.class))
			.member(member)
			.rating(rating)
			.repurchaseIntent("yes")
			.sweetness("medium")
			.fillingAmount("full")
			.textureTags(List.of(TextureTag.CRISPY))
			.content("맛있어요")
			.build();
		ReflectionTestUtils.setField(review, "id", id);
		ReflectionTestUtils.setField(review, "likeCount", likeCount);
		ReflectionTestUtils.setField(review, "createdAt", createdAt);
		return review;
	}

	private Review reviewWithMenu(Long id, Member member, BigDecimal rating, int likeCount, LocalDateTime createdAt) {
		Review review = review(id, member, rating, likeCount, createdAt);
		ReflectionTestUtils.setField(review, "menu", menu());
		return review;
	}

	private Menu menu() {
		Store store = Store.builder()
			.name("단팥당")
			.address("서울 마포구")
			.build();
		ReflectionTestUtils.setField(store, "id", 3L);
		Menu menu = Menu.builder()
			.store(store)
			.name("소금빵")
			.price(3000)
			.build();
		ReflectionTestUtils.setField(menu, "id", 2L);
		return menu;
	}

	private ReviewImageFile image(Long id, Review review, String awsKey) {
		ReviewImageFile image = ReviewImageFile.builder()
			.review(review)
			.name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(awsKey)
			.displayOrder(0)
			.build();
		ReflectionTestUtils.setField(image, "id", id);
		return image;
	}

	private ReviewAuthorAggregateProjection authorAggregate(Long memberId, Long reviewCount, Double averageRating) {
		return new ReviewAuthorAggregateProjection() {
			@Override
			public Long getMemberId() {
				return memberId;
			}

			@Override
			public Long getReviewCount() {
				return reviewCount;
			}

			@Override
			public Double getAverageRating() {
				return averageRating;
			}
		};
	}
}
