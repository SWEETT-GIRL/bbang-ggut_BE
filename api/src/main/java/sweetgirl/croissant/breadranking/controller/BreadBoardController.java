package sweetgirl.croissant.breadranking.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.dto.response.BreadBoardResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.service.BreadBoardQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

/** 랭킹 탭 "빵집" — 빵 종류별 가게 순위. 전국만, 게스트 OK. */
@Tag(name = "BreadRanking", description = "빵 랭킹 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-rankings")
public class BreadBoardController {

	private final BreadBoardQueryService boardQueryService;

	@Operation(summary = "빵집 랭킹 탭 목록", description = "서로 다른 10명 이상이 1등을 매긴 빵 종류만, 사람 수(voters) 많은 순. "
		+ "앞 4개는 탭, 나머지는 '전체' 격자 — 나누는 건 앱 몫. 게스트 OK.")
	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<List<BreadCategoryResponse>>> getBoardCategories() {
		return ResponseEntity.ok(ApiResponse.ok(boardQueryService.getBoardCategories()));
	}

	@Operation(summary = "빵 종류별 가게 순위", description = "1등으로 매긴 사람 수 순(같으면 또 먹을래요 % → 최근 표 → 가게 id). 공동 순위 없음. "
		+ "전체 3표 미만이면 entries 가 비고 missingVotes 에 모자란 수. limit 기본 50, 최대 100. "
		+ "rankChange·isNew 는 어제 스냅샷 대비 — 어제 것이 없으면 null. 게스트 OK.")
	@GetMapping("/boards/{categoryId}")
	public ResponseEntity<ApiResponse<BreadBoardResponse>> getBoard(
			@PathVariable Long categoryId,
			@RequestParam(required = false) Integer limit) {
		return ResponseEntity.ok(ApiResponse.ok(boardQueryService.getBoard(categoryId, limit)));
	}
}
