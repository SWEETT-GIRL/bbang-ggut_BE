package sweetgirl.croissant.recommendation.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.global.logging.SessionIdHeader;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.recommendation.dto.request.CourseStoreBulkAddRequest;
import sweetgirl.croissant.recommendation.dto.request.RecommendationFeedbackRequest;
import sweetgirl.croissant.recommendation.dto.request.RecommendationRequest;
import sweetgirl.croissant.recommendation.dto.response.AnchorRecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.ReasonSentencesResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationContextResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResponse;
import sweetgirl.croissant.recommendation.service.CourseRecommendationCommandService;
import sweetgirl.croissant.recommendation.service.ReasonSentenceCommandService;
import sweetgirl.croissant.recommendation.service.RecommendationContextQueryService;
import sweetgirl.croissant.recommendation.service.RecommendationFeedbackCommandService;

/** 취향 기반 코스 추천 API (설계 §6 · #660). 경로가 /courses 와 /recommendations 에 걸쳐 있어 메서드마다 전체 경로를 적는다. */
@Tag(name = "Recommendation", description = "취향 기반 코스 추천 API")
@RestController
@RequiredArgsConstructor
public class RecommendationController {

	private final RecommendationContextQueryService recommendationContextQueryService;
	private final CourseRecommendationCommandService courseRecommendationCommandService;
	private final RecommendationFeedbackCommandService recommendationFeedbackCommandService;
	private final ReasonSentenceCommandService reasonSentenceCommandService;

	@Operation(summary = "빈 코스 카드 상태", description = "CHIPS 면 상권 칩 3개, QUIZ 면 같은 자리에 빵BTI 4문항.")
	@GetMapping("/courses/{courseId}/recommendation-context")
	public ResponseEntity<ApiResponse<RecommendationContextResponse>> getContext(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@RequestHeader(value = SessionIdHeader.NAME, required = false) String sessionId) {
		return ResponseEntity.ok(ApiResponse.ok(recommendationContextQueryService.getContext(memberId, courseId,
			SessionIdHeader.normalize(sessionId))));
	}

	@Operation(summary = "상권 하나로 코스 추천", description = "같은 코스·상권·일차·프로필이면 저장된 결과를 다시 준다.")
	@PostMapping("/courses/{courseId}/recommendations")
	public ResponseEntity<ApiResponse<RecommendationResponse>> recommend(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody RecommendationRequest request,
			@RequestHeader(value = SessionIdHeader.NAME, required = false) String sessionId) {
		return ResponseEntity.ok(
			ApiResponse.ok(courseRecommendationCommandService.recommend(memberId, courseId, request,
				SessionIdHeader.normalize(sessionId))));
	}

	@Operation(summary = "방금 담은 가게 근처 추천", description = "룰만, LLM 없음. page 는 0부터 9곳씩, recommendationId 는 page 마다 같다.")
	@GetMapping("/courses/{courseId}/anchor-recommendations")
	public ResponseEntity<ApiResponse<AnchorRecommendationResponse>> recommendNearAnchor(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@RequestParam Long storeId,
			@RequestParam(defaultValue = "0") int page) {
		return ResponseEntity.ok(ApiResponse.ok(
			courseRecommendationCommandService.recommendNearAnchor(memberId, courseId, storeId, page)));
	}

	@Operation(summary = "추천 결과 한 번에 담기", description = "한 트랜잭션 — 하나라도 못 담으면 전부 되돌린다. recommendationId 는 내 것이어야 한다.")
	@PostMapping("/courses/{courseId}/stores/bulk")
	public ResponseEntity<ApiResponse<List<CourseStoreResponse>>> addRecommendedStores(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseStoreBulkAddRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			courseRecommendationCommandService.addRecommendedStores(memberId, courseId, request)));
	}

	@Operation(summary = "추천 이유 문장 받기", description = "코스를 먼저 그린 뒤 문장만 따로 받는다. 꺼져 있거나 실패·타임아웃이면 룰 문장과 fallback=true.")
	@PostMapping("/recommendations/{recommendationId}/reasons")
	public ResponseEntity<ApiResponse<ReasonSentencesResponse>> fillReasons(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long recommendationId) {
		return ResponseEntity.ok(ApiResponse.ok(reasonSentenceCommandService.fill(memberId, recommendationId)));
	}

	@Operation(summary = "추천 반응 남기기", description = "가게 단위 반응(STORE_TAP · STORE_UNCHECK)엔 storeId, DISLIKE 엔 reason.")
	@PostMapping("/recommendations/{recommendationId}/feedback")
	public ResponseEntity<Void> recordFeedback(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long recommendationId,
			@Valid @RequestBody RecommendationFeedbackRequest request) {
		recommendationFeedbackCommandService.record(memberId, recommendationId, request);
		return ResponseEntity.noContent().build();
	}
}
