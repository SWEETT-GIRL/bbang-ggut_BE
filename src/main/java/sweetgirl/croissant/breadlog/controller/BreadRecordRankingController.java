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
import sweetgirl.croissant.breadlog.dto.request.RankingRegionVerifyRequest;
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
		+ "scope=LOCAL 이면 인증한 내 지역(시·군·구) 판이고 WEEKLY·RECORDS 에만 통한다 — 로그인하고 위치를 인증했어야 한다"
		+ "(안 했으면 400 RANKING_REGION_NOT_SET). 상위 limit 명(최대 100)과 내 순위, 한 칸 위로 가는 목표를 준다. 게스트는 me 가 null.")
	@GetMapping
	public ResponseEntity<ApiResponse<BreadRecordRankingResponse>> getRanking(
			@AuthenticationPrincipal Long memberId,
			@RequestParam RankingBoard board,
			@RequestParam(defaultValue = "NATIONWIDE") RankingScope scope,
			@RequestParam(defaultValue = "100") int limit) {
		return ResponseEntity.ok(ApiResponse.ok(rankingService.getRanking(memberId, board, scope, limit)));
	}

	@Operation(summary = "랭킹 내 지역 조회", description = "인증한 시·도·시·군·구와 인증 시각. 아직 안 했으면 전부 null. "
		+ "sigungu 만 null 이면 시·도만 고르던 때의 값이라 다시 인증해야 내 지역 판을 볼 수 있다.")
	@GetMapping("/region")
	public ResponseEntity<ApiResponse<RankingRegionResponse>> getRegion(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(regionService.getRegion(memberId)));
	}

	@Operation(summary = "랭킹 내 지역 인증", description = "지금 있는 곳의 좌표를 보내면 서버가 시·군·구(동대문구·수원시)로 바꿔 저장한다. "
		+ "어디서든 다시 인증하면 바로 바뀐다 — 잠금 없음. 한국 범위 밖 좌표는 400, 지역을 못 찾으면 400 RANKING_INVALID_REGION, "
		+ "카카오 장애는 502 KAKAO502_2. 게스트는 403.")
	@PutMapping("/region")
	public ResponseEntity<ApiResponse<RankingRegionResponse>> verifyRegion(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody RankingRegionVerifyRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			regionService.verifyRegion(memberId, request.latitude(), request.longitude())));
	}
}
