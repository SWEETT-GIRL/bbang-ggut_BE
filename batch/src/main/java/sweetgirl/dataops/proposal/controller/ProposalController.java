package sweetgirl.dataops.proposal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.proposal.dto.request.RejectProposalRequest;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.dto.response.LocationGateResponse;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.proposal.service.ProposalQueryService;

@Tag(name = "Proposal", description = "변경 제안 승인함 — 자동 반영 불가 diff 를 사람이 판정 (승인 시 운영 반영)")
@RestController
@RequestMapping("/api/proposals")
@RequiredArgsConstructor
public class ProposalController {

	private final ProposalCommandService proposalCommandService;
	private final ProposalQueryService proposalQueryService;

	@Operation(summary = "제안 목록", description = "기본 PENDING 최신순. snapshotId 로 근거 원본을 조회할 수 있다 (GET /api/snapshots/{id})")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<ChangeProposalResponse>>> getProposals(
			@RequestParam(required = false) ProposalStatus status,
			@RequestParam(required = false) Long storeId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(proposalQueryService.getProposals(status, storeId, page, size)));
	}

	@Operation(summary = "위치 게이트 미리보기 (읽기 전용)", description = "store.address/store.region 제안을 승인하면 "
		+ "어떤 법정동 근거로 지역이 함께 쓰이는지, 보류라면 왜인지 보여 준다. 점유·운영 쓰기 없음 (v3 P4-4)")
	@GetMapping("/{proposalId}/location-gate")
	public ResponseEntity<ApiResponse<LocationGateResponse>> previewLocationGate(@PathVariable Long proposalId) {
		return ResponseEntity.ok(ApiResponse.ok(proposalQueryService.previewLocationGate(proposalId)));
	}

	@Operation(summary = "승인 → 운영 반영", description = "apply_log 선기록 → ServingWriter DML → provenance 기록 (source=데이터 원천)")
	@PatchMapping("/{proposalId}/approve")
	public ResponseEntity<ApiResponse<ChangeProposalResponse>> approve(@PathVariable Long proposalId) {
		return ResponseEntity.ok(ApiResponse.ok(proposalCommandService.approve(proposalId)));
	}

	@Operation(summary = "거절", description = "사유와 함께 REJECTED — 운영 반영 없음")
	@PatchMapping("/{proposalId}/reject")
	public ResponseEntity<ApiResponse<ChangeProposalResponse>> reject(
			@PathVariable Long proposalId,
			@RequestBody @Valid RejectProposalRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(proposalCommandService.reject(proposalId, request.note())));
	}
}
