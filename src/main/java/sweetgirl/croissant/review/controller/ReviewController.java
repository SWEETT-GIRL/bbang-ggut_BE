package sweetgirl.croissant.review.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.review.dto.request.ReviewCreateRequest;
import sweetgirl.croissant.review.dto.request.ReviewPresignedUrlRequest;
import sweetgirl.croissant.review.dto.request.ReviewUpdateRequest;
import sweetgirl.croissant.review.dto.response.MyReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewCreateResponse;
import sweetgirl.croissant.review.dto.response.ReviewDetailResponse;
import sweetgirl.croissant.review.dto.response.ReviewListResponse;
import sweetgirl.croissant.review.dto.response.ReviewPresignedUrlInfoResponse;
import sweetgirl.croissant.review.dto.response.ReviewSummaryResponse;
import sweetgirl.croissant.review.service.ReviewService;

@Tag(name = "Review", description = "리뷰 API")
@RestController
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;

	@Operation(summary = "메뉴 리뷰 요약 조회", description = "평균 별점, 리뷰 수, 미리보기 리뷰를 반환합니다.")
	@GetMapping("/menus/{menuId}/reviews/summary")
	public ResponseEntity<ApiResponse<ReviewSummaryResponse>> getReviewSummary(
			@PathVariable Long menuId,
			@RequestParam(defaultValue = "liked") String sort,
			@RequestParam(defaultValue = "false") boolean photoOnly,
			@RequestParam(defaultValue = "2") int previewSize,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(
			reviewService.getReviewSummary(menuId, sort, photoOnly, previewSize, memberId)));
	}

	@Operation(summary = "메뉴 리뷰 목록 조회", description = "정렬·사진 필터·페이지 조건에 맞는 리뷰 목록을 반환합니다.")
	@GetMapping("/menus/{menuId}/reviews")
	public ResponseEntity<ApiResponse<ReviewListResponse>> getReviews(
			@PathVariable Long menuId,
			@RequestParam(defaultValue = "recent") String sort,
			@RequestParam(defaultValue = "false") boolean photoOnly,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(
			reviewService.getReviews(menuId, sort, photoOnly, page, size, memberId)));
	}

	@Operation(summary = "리뷰 상세 조회", description = "리뷰 ID로 리뷰 상세 정보를 조회합니다.")
	@GetMapping("/reviews/{reviewId}")
	public ResponseEntity<ApiResponse<ReviewDetailResponse>> getReviewDetail(
			@PathVariable Long reviewId,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(reviewService.getReviewDetail(reviewId, memberId)));
	}

	@Operation(summary = "내가 쓴 리뷰 목록 조회", description = "로그인 사용자가 작성한 리뷰 목록을 최신순으로 조회합니다.")
	@GetMapping("/users/me/reviews")
	public ResponseEntity<ApiResponse<MyReviewListResponse>> getMyReviews(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(reviewService.getMyReviews(memberId, page, size)));
	}

	@Operation(summary = "사용자가 쓴 리뷰 목록 조회", description = "공개 프로필에서 특정 사용자가 작성한 리뷰 목록을 "
		+ "최신순으로 조회합니다.")
	@GetMapping("/users/{memberId}/reviews")
	public ResponseEntity<ApiResponse<MyReviewListResponse>> getMemberReviews(
			@PathVariable Long memberId,
			@AuthenticationPrincipal Long viewerMemberId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(
			reviewService.getMemberReviews(memberId, viewerMemberId, page, size)));
	}

	@Operation(summary = "리뷰 이미지 Presigned URL 발급", description = "mimeTypes 배열 크기만큼 리뷰 이미지 업로드 URL을 반환합니다.")
	@PostMapping("/reviews/images/presigned-urls")
	public ResponseEntity<ApiResponse<ReviewPresignedUrlInfoResponse>> getReviewPresignedUrls(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody ReviewPresignedUrlRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(reviewService.generateReviewPresignedUrls(memberId, request)));
	}

	@Operation(summary = "메뉴 리뷰 생성", description = "로그인 사용자가 메뉴에 리뷰를 작성합니다.")
	@PostMapping("/menus/{menuId}/reviews")
	public ResponseEntity<ApiResponse<ReviewCreateResponse>> createReview(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody ReviewCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(reviewService.createReview(menuId, memberId, request)));
	}

	@Operation(summary = "작성한 리뷰 수정", description = "로그인 사용자가 본인이 작성한 리뷰를 수정합니다.")
	@PatchMapping("/reviews/{reviewId}")
	public ResponseEntity<ApiResponse<ReviewDetailResponse>> updateReview(
			@PathVariable Long reviewId,
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody ReviewUpdateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(reviewService.updateReview(reviewId, memberId, request)));
	}

	@Operation(summary = "작성한 리뷰 삭제", description = "로그인 사용자가 본인이 작성한 리뷰를 삭제합니다.")
	@DeleteMapping("/reviews/{reviewId}")
	public ResponseEntity<ApiResponse<Void>> deleteReview(
			@PathVariable Long reviewId,
			@AuthenticationPrincipal Long memberId) {
		reviewService.deleteReview(reviewId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
