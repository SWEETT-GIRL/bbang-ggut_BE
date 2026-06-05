package sweetgirl.croissant.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.review.code.ReviewErrorCode;
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
import sweetgirl.croissant.review.enums.TextureTag;
import sweetgirl.croissant.review.exception.ReviewException;
import sweetgirl.croissant.review.service.ReviewService;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ReviewService reviewService;

	@InjectMocks
	private ReviewController reviewController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(reviewController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getReviewSummary_returnsPreviewReviews() throws Exception {
		given(reviewService.getReviewSummary(eq(1L), eq("liked"), eq(false), eq(2), isNull()))
			.willReturn(new ReviewSummaryResponse(1L, BigDecimal.valueOf(4.5), 3L, List.of(review())));

		mockMvc.perform(get("/menus/1/reviews/summary"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.menuId").value(1))
			.andExpect(jsonPath("$.data.averageRating").value(4.5))
			.andExpect(jsonPath("$.data.previewReviews[0].author.name").value("단팥"))
			.andExpect(jsonPath("$.data.previewReviews[0].photos[0].awsKey").value("reviews/members/10/images/a"));
	}

	@Test
	void getReviews_returnsContentAndHasNext() throws Exception {
		given(reviewService.getReviews(eq(1L), eq("recent"), eq(false), eq(0), eq(20), isNull()))
			.willReturn(new ReviewListResponse(1L, BigDecimal.valueOf(4.0), 1L, List.of(review()), false));

		mockMvc.perform(get("/menus/1/reviews"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void getReviewDetail_returnsDetail() throws Exception {
		given(reviewService.getReviewDetail(eq(1L), isNull()))
			.willReturn(reviewDetail());

		mockMvc.perform(get("/reviews/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.menuName").value("소금빵"))
			.andExpect(jsonPath("$.data.storeName").value("단팥당"));
	}

	@Test
	void getMyReviews_returnsContentAndHasNext() throws Exception {
		given(reviewService.getMyReviews(isNull(), eq(0), eq(20)))
			.willReturn(new MyReviewListResponse(List.of(reviewDetail()), false));

		mockMvc.perform(get("/users/me/reviews"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void getReviewPresignedUrls_returns401_whenMemberIdIsNull() throws Exception {
		given(reviewService.generateReviewPresignedUrls(isNull(), any()))
			.willThrow(new MemberException(MemberErrorCode.UNAUTHORIZED));

		mockMvc.perform(post("/reviews/images/presigned-urls")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"mimeTypes\":[\"image/jpeg\"]}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void getReviewPresignedUrls_returnsUrls() throws Exception {
		given(reviewService.generateReviewPresignedUrls(isNull(), any()))
			.willReturn(new ReviewPresignedUrlInfoResponse(List.of(
				new ReviewPresignedUrlInfo("https://upload", "reviews/members/7/images/a"))));

		mockMvc.perform(post("/reviews/images/presigned-urls")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"mimeTypes\":[\"image/jpeg\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.presignedUrls[0].uploadUrl").value("https://upload"));
	}

	@Test
	void createReview_returns201() throws Exception {
		given(reviewService.createReview(eq(1L), isNull(), any()))
			.willReturn(new ReviewCreateResponse(11L, LocalDateTime.of(2026, 6, 4, 11, 0)));

		mockMvc.perform(post("/menus/1/reviews")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rating\":4.5,\"repurchaseIntent\":\"yes\","
				+ "\"sweetness\":\"medium\",\"fillingAmount\":\"full\","
				+ "\"textureTags\":[\"crispy\"],\"content\":\"맛있어요\",\"images\":[]}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reviewId").value(11));
	}

	@Test
	void createReview_returns400_whenContentRejected() throws Exception {
		given(reviewService.createReview(eq(1L), isNull(), any()))
			.willThrow(new ReviewException(ReviewErrorCode.REVIEW_CONTENT_REJECTED));

		mockMvc.perform(post("/menus/1/reviews")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rating\":4.5,\"repurchaseIntent\":\"yes\","
				+ "\"sweetness\":\"medium\",\"fillingAmount\":\"full\","
				+ "\"textureTags\":[\"crispy\"],\"content\":\"010-1234-5678\",\"images\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("REVIEW_CONTENT_REJECTED"))
			.andExpect(jsonPath("$.message")
				.value("부적절하거나 개인정보로 보이는 표현이 포함되어 있어 등록할 수 없습니다."));
	}

	@Test
	void updateReview_returnsDetail() throws Exception {
		given(reviewService.updateReview(eq(1L), isNull(), any()))
			.willReturn(reviewDetail());

		mockMvc.perform(patch("/reviews/1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rating\":4.0,\"repurchaseIntent\":\"yes\","
				+ "\"sweetness\":\"medium\",\"fillingAmount\":\"full\","
				+ "\"textureTags\":[\"chewy\"],\"content\":\"수정했어요\",\"images\":[]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.content").value("맛있어요"));
	}

	@Test
	void updateReview_returns400_whenContentRejected() throws Exception {
		given(reviewService.updateReview(eq(1L), isNull(), any()))
			.willThrow(new ReviewException(ReviewErrorCode.REVIEW_CONTENT_REJECTED));

		mockMvc.perform(patch("/reviews/1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rating\":4.0,\"repurchaseIntent\":\"yes\","
				+ "\"sweetness\":\"medium\",\"fillingAmount\":\"full\","
				+ "\"textureTags\":[\"chewy\"],\"content\":\"owner@example.com\",\"images\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("REVIEW_CONTENT_REJECTED"));
	}

	@Test
	void deleteReview_returns200() throws Exception {
		mockMvc.perform(delete("/reviews/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	private MenuReviewResponse review() {
		return new MenuReviewResponse(
			1L,
			BigDecimal.valueOf(4.5),
			"yes",
			"medium",
			"full",
			List.of(TextureTag.CRISPY),
			"맛있어요",
			7,
			0,
			true,
			new ReviewAuthorResponse(10L, "단팥", 3L, BigDecimal.valueOf(4.0), null, null),
			List.of(new ReviewPhotoResponse(100L, "reviews/members/10/images/a")),
			LocalDateTime.of(2026, 6, 4, 10, 0));
	}

	private ReviewDetailResponse reviewDetail() {
		return new ReviewDetailResponse(
			1L,
			2L,
			"소금빵",
			3L,
			"단팥당",
			BigDecimal.valueOf(4.5),
			"yes",
			"medium",
			"full",
			List.of(TextureTag.CRISPY),
			"맛있어요",
			7,
			0,
			true,
			new ReviewAuthorResponse(10L, "단팥", 3L, BigDecimal.valueOf(4.0), null, null),
			List.of(new ReviewPhotoResponse(100L, "reviews/members/10/images/a")),
			LocalDateTime.of(2026, 6, 4, 10, 0),
			null);
	}
}
