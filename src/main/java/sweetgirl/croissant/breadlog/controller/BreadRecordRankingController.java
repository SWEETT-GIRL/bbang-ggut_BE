package sweetgirl.croissant.breadlog.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.request.RankingRegionUpdateRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.enums.RankingBoard;
import sweetgirl.croissant.breadlog.enums.RankingScope;
import sweetgirl.croissant.breadlog.service.BreadRecordRankingService;
import sweetgirl.croissant.breadlog.service.RankingRegionService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "BreadLog", description = "빵 기록 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-records/ranking")
public class BreadRecordRankingController {

	private final BreadRecordRankingService rankingService;
	private final RankingRegionService regionService;

	@Operation(summary = "빵로그 랭킹", description = "board: WEEKLY(이번 주 빵로그왕)·RECORDS(역대 빵로그왕)·"
		+ "REGIONS(전국 정복왕)·SHARE(공유왕). "
		+ "scope=LOCAL 이면 저장된 내 지역(시·도) 판이고 WEEKLY·RECORDS 에만 통한다 — 로그인하고 지역을 정했어야 한다. "
		+ "상위 limit 명(최대 100)과 내 순위, 한 칸 위로 가는 목표를 준다. 게스트는 me 가 null.")
	@GetMapping
	public ResponseEntity<ApiResponse<BreadRecordRankingResponse>> getRanking(
			@AuthenticationPrincipal Long memberId,
			@RequestParam RankingBoard board,
			@RequestParam(defaultValue = "NATIONWIDE") RankingScope scope,
			@RequestParam(defaultValue = "100") int limit) {
		return ResponseEntity.ok(ApiResponse.ok(rankingService.getRanking(memberId, board, scope, limit)));
	}

	@Operation(summary = "랭킹 내 지역 조회", description = "안 골랐으면 sido 가 null. nextChangeableAt 이 null 이면 지금 바꿀 수 있다.")
	@GetMapping("/region")
	public ResponseEntity<ApiResponse<RankingRegionResponse>> getRegion(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(regionService.getRegion(memberId)));
	}

	@Operation(summary = "랭킹 내 지역 정하기", description = "짧은 시·도(서울·경기…). 30일에 한 번 바꿀 수 있고, "
		+ "처음 정한 뒤 24시간 안의 한 번은 바로 바꿀 수 있다. 아직 못 바꾸면 409 RANKING_REGION_LOCKED. 게스트는 403.")
	@PutMapping("/region")
	public ResponseEntity<ApiResponse<RankingRegionResponse>> updateRegion(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody RankingRegionUpdateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(regionService.updateRegion(memberId, request.sido())));
	}
}
