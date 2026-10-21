package sweetgirl.croissant.breadranking.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;
import sweetgirl.croissant.breadranking.dto.request.BreadRankPlaceRequest;
import sweetgirl.croissant.breadranking.dto.request.BreadRankStoreAddRequest;
import sweetgirl.croissant.breadranking.dto.request.MyBreadCategoryAddRequest;
import sweetgirl.croissant.breadranking.dto.response.MyBreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingChangeResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.service.MyBreadRankingCommandService;
import sweetgirl.croissant.breadranking.service.MyBreadRankingQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

/** 마이 → 내 빵 순위. 전부 로그인 필요(게스트 403). 1등 = 그 빵 종류의 내 한 표. */
@Tag(name = "BreadRanking", description = "빵 랭킹 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-rankings/me")
public class MyBreadRankingController {

	private final MyBreadRankingQueryService rankingQueryService;
	private final MyBreadRankingCommandService rankingCommandService;

	@Operation(summary = "내 빵 순위 탭 목록", description = "내가 추가한 빵 종류(순서)와 그 종류의 내 1등 가게(공동이면 여러 개). "
		+ "마이 '내 빵 순위' 줄은 첫 탭의 첫 가게를 쓴다.")
	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<List<MyBreadCategoryResponse>>> getMyCategories(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(rankingQueryService.getMyCategories(memberId)));
	}

	@Operation(summary = "내 탭에 빵 종류 추가", description = "맨 뒤에 붙는다. 이미 있으면 그대로. 바뀐 탭 목록을 준다.")
	@PostMapping("/categories")
	public ResponseEntity<ApiResponse<List<MyBreadCategoryResponse>>> addCategory(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody MyBreadCategoryAddRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(rankingCommandService.addCategory(memberId, request.categoryId())));
	}

	@Operation(summary = "내 탭에서 빵 종류 빼기", description = "그 종류의 내 순위(1등 표 포함)도 같이 지워진다. 바뀐 탭 목록을 준다.")
	@DeleteMapping("/categories/{categoryId}")
	public ResponseEntity<ApiResponse<List<MyBreadCategoryResponse>>> removeCategory(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId) {
		return ResponseEntity.ok(ApiResponse.ok(rankingCommandService.removeCategory(memberId, categoryId)));
	}

	@Operation(summary = "한 빵 종류의 내 순위", description = "등수 순(공동 포함)과 아직 안 정한 가게. 줄마다 가게 이름·사진·동네와 "
		+ "그 가게에 남긴 내 최신 빵 평가(없으면 null).")
	@GetMapping("/{categoryId}")
	public ResponseEntity<ApiResponse<MyBreadRankingResponse>> getMyRanking(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId) {
		return ResponseEntity.ok(ApiResponse.ok(rankingQueryService.getMyRanking(memberId, categoryId)));
	}

	@Operation(summary = "담을 가게 후보", description = "빵로그를 남긴 가게 중 이 종류에 아직 안 담은 것. query 는 가게 이름 부분 일치.")
	@GetMapping("/{categoryId}/candidates")
	public ResponseEntity<ApiResponse<List<StoreSummaryResponse>>> getCandidates(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId,
			@RequestParam(required = false) String query) {
		return ResponseEntity.ok(ApiResponse.ok(rankingQueryService.getCandidates(memberId, categoryId, query)));
	}

	@Operation(summary = "가게 담기", description = "등수 없이 담는다(빵로그 저장 뒤 [순위 매기기]도 이걸 부른다). 탭에 없으면 탭도 추가. "
		+ "빵로그를 남기지 않은 가게는 400 BREAD_RANK_NOT_EATEN.")
	@PostMapping("/{categoryId}/stores")
	public ResponseEntity<ApiResponse<MyBreadRankingResponse>> addStore(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId,
			@Valid @RequestBody BreadRankStoreAddRequest request) {
		return ResponseEntity
			.ok(ApiResponse.ok(rankingCommandService.addStore(memberId, categoryId, request.storeId())));
	}

	@Operation(summary = "등수 정하기", description = "rank 는 1부터 마지막 등수 + 1 까지(넘으면 400 BREAD_RANK_INVALID_RANK). "
		+ "mode TIE 는 같이 서고, INSERT 는 끼우고 뒤를 민다. 1등이 바뀌면 topChanged 와 이전·새 1등을 준다.")
	@PutMapping("/{categoryId}/stores/{storeId}/rank")
	public ResponseEntity<ApiResponse<MyBreadRankingChangeResponse>> placeStore(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId,
			@PathVariable Long storeId,
			@Valid @RequestBody BreadRankPlaceRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			rankingCommandService.placeStore(memberId, categoryId, storeId, request.rank(), request.mode())));
	}

	@Operation(summary = "가게 빼기", description = "남은 줄은 1부터 다시 번호를 매긴다. 1등이 바뀌면 topChanged.")
	@DeleteMapping("/{categoryId}/stores/{storeId}")
	public ResponseEntity<ApiResponse<MyBreadRankingChangeResponse>> removeStore(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId,
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(rankingCommandService.removeStore(memberId, categoryId, storeId)));
	}

	@Operation(summary = "그 가게의 내 빵 평가 고치기", description = "가게 시트. 그 가게 × 빵 종류의 내 최신 빵로그 태그 평가를 바꾼다. "
		+ "태그가 없으면 404 BREAD_RATING_NOT_FOUND. 가격은 메뉴 가격 API(PATCH /stores/{storeId}/menus/{menuId}/price)로.")
	@PatchMapping("/{categoryId}/stores/{storeId}/rating")
	public ResponseEntity<ApiResponse<MyBreadRatingResponse>> updateRating(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long categoryId,
			@PathVariable Long storeId,
			@Valid @RequestBody BreadTagRatingRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			rankingCommandService.updateRating(memberId, categoryId, storeId, request)));
	}
}
