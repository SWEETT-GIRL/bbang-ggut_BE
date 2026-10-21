package sweetgirl.croissant.breadranking.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse;
import sweetgirl.croissant.breadranking.service.StoreBreadRatingQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "BreadRanking", description = "빵 랭킹 API")
@RestController
@RequiredArgsConstructor
public class StoreBreadRatingController {

	private final StoreBreadRatingQueryService storeBreadRatingQueryService;

	@Operation(summary = "가게의 빵 종류별 평가", description = "평가한 사람 5명 이상인 종류만 — 전국 등수(없으면 null)·또 먹을래요 %·식감 상위 3·"
		+ "가장 많이 태그된 메뉴와 가격. 없는 가게는 404. 게스트 OK.")
	@GetMapping("/stores/{storeId}/bread-ratings")
	public ResponseEntity<ApiResponse<List<StoreBreadRatingResponse>>> getStoreRatings(@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(storeBreadRatingQueryService.getStoreRatings(storeId)));
	}
}
