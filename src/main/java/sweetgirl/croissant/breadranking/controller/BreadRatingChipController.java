package sweetgirl.croissant.breadranking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse;
import sweetgirl.croissant.breadranking.service.BreadRatingChipQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "BreadRanking", description = "빵 랭킹 API")
@RestController
@RequiredArgsConstructor
public class BreadRatingChipController {

	private final BreadRatingChipQueryService chipQueryService;

	@Operation(summary = "빵 평가 칩 목록", description = "식감 16 · 맛 10 전체를 노출 순서대로 준다. 앞 6개가 펼쳐 보이는 것. "
		+ "menuKind 는 빵로그 태그의 breadCategory(MenuKind 이름) — 대분류가 있으면 그 순서, 없거나 모르면 기본 순서. 게스트 OK.")
	@GetMapping("/bread-rating-chips")
	public ResponseEntity<ApiResponse<BreadRatingChipsResponse>> getChips(
			@RequestParam(required = false) String menuKind) {
		return ResponseEntity.ok(ApiResponse.ok(chipQueryService.getChips(menuKind)));
	}
}
