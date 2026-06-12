package sweetgirl.croissant.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminModerationActionRequest;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.service.AdminReportService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Report", description = "어드민 제보 관리 API")
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

	private final AdminReportService adminReportService;

	@Operation(summary = "제보 목록 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<Page<AdminReportResponse>>> getReports(
			@Parameter(description = "제보 종류") @RequestParam(required = false) String kind,
			@Parameter(description = "처리 상태") @RequestParam(required = false) String status,
			@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(adminReportService.getReports(kind, status, page, size)));
	}

	@Operation(summary = "사용자 신고 통계 조회")
	@GetMapping("/stats")
	public ResponseEntity<ApiResponse<AdminAbuseReportStatsResponse>> getAbuseReportStats() {
		return ResponseEntity.ok(ApiResponse.ok(adminReportService.getAbuseReportStats()));
	}

	@Operation(summary = "사용자 신고 high-priority 큐 조회")
	@GetMapping("/high-priority")
	public ResponseEntity<ApiResponse<Page<AdminHighPriorityReportResponse>>> getHighPriorityReports(
			@Parameter(description = "처리 상태") @RequestParam(defaultValue = "PENDING") String status,
			@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(adminReportService.getHighPriorityReports(status, page, size)));
	}

	@Operation(summary = "제보 상세 조회")
	@GetMapping("/{reportId}")
	public ResponseEntity<ApiResponse<AdminReportDetailResponse>> getReportDetail(
			@Parameter(description = "제보 ID") @PathVariable Long reportId,
			@Parameter(description = "제보 종류") @RequestParam String kind) {
		return ResponseEntity.ok(ApiResponse.ok(adminReportService.getReportDetail(reportId, kind)));
	}

	@Operation(summary = "사용자 신고 moderation action 적용")
	@PostMapping("/{reportId}/moderation-actions")
	public ResponseEntity<ApiResponse<AdminModerationActionResponse>> applyModerationAction(
			@Parameter(description = "제보 ID") @PathVariable Long reportId,
			@Valid @RequestBody AdminModerationActionRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminReportService.applyModerationAction(reportId, request)));
	}

	@Operation(summary = "제보 승인/거절", description = "제보를 승인(ACCEPT) 또는 거절(REJECT)합니다. "
		+ "kind: STORE_REPORT | MENU_ADD_REPORT | ABUSE_REPORT, action: ACCEPT | REJECT")
	@PatchMapping("/{reportId}")
	public ResponseEntity<ApiResponse<Void>> reviewReport(
			@Parameter(description = "제보 ID") @PathVariable Long reportId,
			@Valid @RequestBody AdminReportReviewRequest request) {
		adminReportService.reviewReport(reportId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
