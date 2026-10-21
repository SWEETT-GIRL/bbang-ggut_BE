package sweetgirl.dataops.matching.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.matching.dto.request.MatchingProposalRequest;
import sweetgirl.dataops.matching.dto.response.MatchingApplyResult;
import sweetgirl.dataops.matching.dto.response.MatchingProposalResult;
import sweetgirl.dataops.matching.service.MatchingApplyService;
import sweetgirl.dataops.matching.service.MatchingCommandService;

@Tag(name = "Matching", description = "미매칭 가게 place_id 반영 — 검색·눈검사 결과를 승인함으로 태운다")
@RestController
@RequestMapping("/api/matching")
@RequiredArgsConstructor
public class MatchingController {

	private final MatchingCommandService matchingCommandService;
	private final MatchingApplyService matchingApplyService;

	@Operation(summary = "매칭 반영 제안 일괄 생성", description = "눈검사 통과 (storeId, naverPlaceId) 목록을 store.naver_place_id proposal 로 적재. 승인은 개별 /api/proposals/{id}/approve")
	@PostMapping("/proposals")
	public ResponseEntity<ApiResponse<MatchingProposalResult>> createProposals(
			@RequestBody @Valid MatchingProposalRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(matchingCommandService.createProposals(request)));
	}

	@Operation(summary = "매칭 배치 직접 반영", description = "눈검사 게이트 통과분을 apply_log 선기록 후 운영에 직접 반영 (proposal 승인 우회 — 이미 사람 검증 완료). 선점 가게는 자동 스킵")
	@PostMapping("/apply")
	public ResponseEntity<ApiResponse<MatchingApplyResult>> applyBatch(
			@RequestBody @Valid MatchingProposalRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(matchingApplyService.applyBatch(request)));
	}
}
