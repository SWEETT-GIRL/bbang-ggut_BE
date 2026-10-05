package sweetgirl.croissant.monthlyreport.admin.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetUploadResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportMemberResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportProgressResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportRunResponse;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetUploadService;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;

@Tag(name = "[Admin] 월간 보고서 관리", description = "월간 보고서 관리 API")
@RestController
@RequestMapping("/api/admin/monthly-reports")
@RequiredArgsConstructor
public class AdminMonthlyReportController {
	private final MonthlyReportAdminCommandService reportCommandService;
	private final MonthlyReportAdminQueryService reportQueryService;
	private final MonthlyReportAssetUploadService assetUploadService;
	private final MonthlyReportAssetQueryService assetQueryService;

	@PostMapping("/assets/upload-urls")
	public ApiResponse<MonthlyReportAssetUploadResponse> issueAssetUploadUrl(
			@Valid @RequestBody MonthlyReportAdminRequests.Asset request) {
		return ApiResponse.ok(assetUploadService.issueAssetUploadUrl(request));
	}

	@PostMapping("/assets")
	public ApiResponse<MonthlyReportAssetResponse> registerReportAsset(
			@Valid @RequestBody MonthlyReportAdminRequests.Asset request) {
		return ApiResponse.ok(assetUploadService.registerReportAsset(request));
	}

	@GetMapping("/assets")
	public ApiResponse<List<MonthlyReportAssetResponse>> getReportAssets(@RequestParam String version) {
		return ApiResponse.ok(assetQueryService.getReportAssets(version));
	}

	@GetMapping("/{month}/runs")
	public ApiResponse<SliceResponse<MonthlyReportRunResponse>> getReportRuns(@PathVariable String month,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(reportQueryService.getReportRuns(month, page, size));
	}

	@PostMapping("/{month}/runs")
	public ResponseEntity<ApiResponse<MonthlyReportRunResponse>> createReportRun(@PathVariable String month,
			@Valid @RequestBody MonthlyReportAdminRequests.CreateRun request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reportCommandService.createReportRun(month, request.assetVersion())));
	}

	@PostMapping("/{month}/members/{memberId}/runs")
	public ResponseEntity<ApiResponse<MonthlyReportRunResponse>> createMemberReportRun(@PathVariable String month,
			@PathVariable Long memberId, @Valid @RequestBody MonthlyReportAdminRequests.CreateRun request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reportCommandService.createReportRun(month, request.assetVersion(), memberId)));
	}

	@GetMapping("/{month}/runs/{id}")
	public ApiResponse<MonthlyReportProgressResponse> getReportRunProgress(@PathVariable String month,
			@PathVariable UUID id) {
		return ApiResponse.ok(reportQueryService.getReportRunProgress(month, id));
	}

	@GetMapping("/{month}/runs/{id}/members")
	public ApiResponse<SliceResponse<MonthlyReportMemberResponse>> getReportRunMembers(@PathVariable String month,
			@PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(reportQueryService.getReportRunMembers(month, id, page, size));
	}

	@GetMapping("/{month}/runs/{id}/preview/{memberId}")
	public ResponseEntity<ApiResponse<MonthlyReportResponse>> getReportPreview(@PathVariable String month,
			@PathVariable UUID id,
			@PathVariable Long memberId) {
		return ResponseEntity.ok().header("Cache-Control", "no-store")
			.body(ApiResponse.ok(reportQueryService.getReportPreview(month, id, memberId)));
	}

	@PostMapping("/{month}/runs/{id}/publish")
	public ResponseEntity<ApiResponse<MonthlyReportRunResponse>> approveReportPublication(@PathVariable String month,
			@PathVariable UUID id,
			@Valid @RequestBody MonthlyReportAdminRequests.Publish request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reportCommandService.approveReportPublication(month, id, request.pushAt())));
	}

	@PostMapping("/{month}/runs/{id}/retry")
	public ResponseEntity<ApiResponse<MonthlyReportRunResponse>> retryReportRun(@PathVariable String month,
			@PathVariable UUID id) {
		return ResponseEntity.accepted().body(ApiResponse.ok(reportCommandService.retryReportRun(month, id)));
	}
}
