package sweetgirl.croissant.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.dto.projection.ReviewAuthorAggregateProjection;
import sweetgirl.croissant.review.dto.response.ReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewSummaryResponse;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.enums.TextureTag;
import sweetgirl.croissant.review.exception.ReviewException;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRecommendRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class ReviewQueryServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MenuRepository menuRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	ReviewImageFileRepository reviewImageFileRepository;
	@Mock
	ReviewRecommendRepository reviewRecommendRepository;

	ReviewQueryService reviewQueryService;

	@BeforeEach
	void setUp() {
		ReviewResponseAssembler reviewResponseAssembler = new ReviewResponseAssembler(
			reviewRepository,
			reviewImageFileRepository,
			reviewRecommendRepository);
		reviewQueryService = new ReviewQueryService(
			memberReader,
			menuRepository,
			reviewRepository,
			reviewResponseAssembler);
	}

	@Test
	@DisplayName("getReviewSummary: recommended 정렬 기본 조회와 로그인 사용자의 isRecommended를 반영한다")
	void getReviewSummary_recommendedSort_returnsPreviewWithRecommendedState() {
		Member author = member(10L, "단팥");
		Review review = review(1L, author, BigDecimal.valueOf(4.5), 7, LocalDateTime.of(2026, 6, 4, 10, 0));
		ReviewImageFile image = image(100L, review, "reviews/members/10/images/a");

		given(menuRepository.findByIdWithDetails(1L)).willReturn(Optional.of(mock(Menu.class)));
		given(reviewRepository.findVisibleActiveReviewsOrderByRecommended(
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
		given(reviewRecommendRepository.findRecommendedReviewIds(99L, List.of(1L))).willReturn(List.of(1L));
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(10L)))
			.willReturn(List.of(authorAggregate(10L, 3L, 4.0)));

		ReviewSummaryResponse result = reviewQueryService.getReviewSummary(1L, "recommended", false, 2, 99L);

		assertThat(result.averageRating()).isEqualByComparingTo("4.5");
		assertThat(result.reviewCount()).isEqualTo(2L);
		assertThat(result.previewReviews()).hasSize(1);
		assertThat(result.previewReviews().get(0).isRecommended()).isTrue();
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

		ReviewListResponse result = reviewQueryService.getReviews(1L, "recent", true, 1, 2, null);

		assertThat(result.content()).hasSize(2);
		assertThat(result.hasNext()).isTrue();
		verify(reviewRepository).findVisibleActiveReviewsOrderByRecent(
			eq(1L),
			eq(true),
			isNull(),
			argThat(pageable -> pageable.getPageNumber() == 1 && pageable.getPageSize() == 3));
		verify(reviewRecommendRepository, never()).findRecommendedReviewIds(any(), any());
	}

	@Test
	@DisplayName("getReviewDetail: 차단한 작성자의 리뷰는 REVIEW_NOT_FOUND로 응답한다")
	void getReviewDetail_blockedAuthor_throwsReviewNotFound() {
		given(reviewRepository.findVisibleActiveByIdWithDetails(1L, 99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> reviewQueryService.getReviewDetail(1L, 99L))
			.isInstanceOf(ReviewException.class)
			.hasMessageContaining(ReviewErrorCode.REVIEW_NOT_FOUND.getMessage());
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
		given(reviewRecommendRepository.findRecommendedReviewIds(7L, List.of(1L))).willReturn(List.of());
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(7L)))
			.willReturn(List.of(authorAggregate(7L, 1L, 4.5)));

		var result = reviewQueryService.getMyReviews(7L, 0, 20);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).storeName()).isEqualTo("단팥당");
		assertThat(result.content().get(0).canEdit()).isTrue();
		assertThat(result.content().get(0).canDelete()).isTrue();
		assertThat(result.hasNext()).isFalse();
	}

	@Test
	@DisplayName("getMemberReviews: 공개 프로필 리뷰를 최신순 slice로 반환하고 "
		+ "viewer 상태를 반영한다")
	void getMemberReviews_returnsPublicReviewsWithViewerState() {
		Member author = member(10L, "단팥");
		Review review = reviewWithMenu(1L, author, BigDecimal.valueOf(4.5), 7, LocalDateTime.now());
		ReviewImageFile image = image(100L, review, "reviews/members/10/images/a");

		given(memberReader.getMember(10L)).willReturn(author);
		given(reviewRepository.findVisibleActiveByMemberIdOrderByRecent(eq(10L), eq(99L), any(Pageable.class)))
			.willReturn(List.of(review));
		given(reviewImageFileRepository.findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List.of(1L)))
			.willReturn(List.of(image));
		given(reviewRecommendRepository.findRecommendedReviewIds(99L, List.of(1L))).willReturn(List.of(1L));
		given(reviewRepository.findAuthorAggregatesByMemberIds(Set.of(10L)))
			.willReturn(List.of(authorAggregate(10L, 3L, 4.0)));

		var result = reviewQueryService.getMemberReviews(10L, 99L, 0, 20);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).isRecommended()).isTrue();
		assertThat(result.content().get(0).canEdit()).isFalse();
		assertThat(result.content().get(0).canDelete()).isFalse();
		assertThat(result.content().get(0).photos().get(0).awsKey())
			.isEqualTo("reviews/members/10/images/a");
		assertThat(result.hasNext()).isFalse();
		verify(reviewRepository).findVisibleActiveByMemberIdOrderByRecent(
			eq(10L),
			eq(99L),
			argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 21));
	}

	@Test
	@DisplayName("getMemberReviews: 존재하지 않는 회원이면 MEMBER_NOT_FOUND를 던진다")
	void getMemberReviews_missingMember_throwsNotFound() {
		given(memberReader.getMember(404L)).willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		assertThatThrownBy(() -> reviewQueryService.getMemberReviews(404L, 99L, 0, 20))
			.isInstanceOf(MemberException.class)
			.hasMessageContaining(MemberErrorCode.MEMBER_NOT_FOUND.getMessage());

		verify(reviewRepository, never()).findVisibleActiveByMemberIdOrderByRecent(any(), any(), any());
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder()
			.nickname(nickname)
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Review review(Long id, Member member, BigDecimal rating, int recommendCount, LocalDateTime createdAt) {
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
		ReflectionTestUtils.setField(review, "recommendCount", recommendCount);
		ReflectionTestUtils.setField(review, "createdAt", createdAt);
		return review;
	}

	private Review reviewWithMenu(Long id, Member member, BigDecimal rating, int recommendCount,
			LocalDateTime createdAt) {
		Review review = review(id, member, rating, recommendCount, createdAt);
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
