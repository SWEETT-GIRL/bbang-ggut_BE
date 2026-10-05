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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetStore;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;

@RestController
@RequestMapping("/api/admin/monthly-reports")
@RequiredArgsConstructor
public class AdminMonthlyReportController {
	private final MonthlyReportAdminService reports;
	private final MonthlyReportAssetService assets;

	@PostMapping("/assets/upload-urls")
	public ApiResponse<MonthlyReportAssetStore.Upload> issueAssetUploadUrl(
			@Valid @RequestBody MonthlyReportAdminRequests.Asset request) {
		return ApiResponse.ok(assets.issueAssetUploadUrl(request));
	}

	@PostMapping("/assets")
	public ApiResponse<MonthlyReportAsset> registerReportAsset(
			@Valid @RequestBody MonthlyReportAdminRequests.Asset request) {
		return ApiResponse.ok(assets.registerReportAsset(request));
	}

	@GetMapping("/assets")
	public ApiResponse<List<MonthlyReportAsset>> getReportAssets(@RequestParam String version) {
		return ApiResponse.ok(assets.getReportAssets(version));
	}

	@GetMapping("/{month}/runs")
	public ApiResponse<SliceResponse<MonthlyReportRun>> getReportRuns(@PathVariable String month,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(reports.getReportRuns(month, page, size));
	}

	@PostMapping("/{month}/runs")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> createReportRun(@PathVariable String month,
			@Valid @RequestBody MonthlyReportAdminRequests.CreateRun request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reports.createReportRun(month, request.assetVersion())));
	}

	@PostMapping("/{month}/members/{memberId}/runs")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> createMemberReportRun(@PathVariable String month,
			@PathVariable Long memberId, @Valid @RequestBody MonthlyReportAdminRequests.CreateRun request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reports.createReportRun(month, request.assetVersion(), memberId)));
	}

	@GetMapping("/{month}/runs/{id}")
	public ApiResponse<MonthlyReportAdminService.Progress> getReportRunProgress(@PathVariable String month,
			@PathVariable UUID id) {
		return ApiResponse.ok(reports.getReportRunProgress(month, id));
	}

	@GetMapping("/{month}/runs/{id}/members")
	public ApiResponse<SliceResponse<MonthlyReportAdminService.Item>> getReportRunMembers(@PathVariable String month,
			@PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(reports.getReportRunMembers(month, id, page, size));
	}

	@GetMapping("/{month}/runs/{id}/preview/{memberId}")
	public ResponseEntity<ApiResponse<MonthlyReportResponse>> getReportPreview(@PathVariable String month,
			@PathVariable UUID id,
			@PathVariable Long memberId) {
		return ResponseEntity.ok().header("Cache-Control", "no-store")
			.body(ApiResponse.ok(reports.getReportPreview(month, id, memberId)));
	}

	@PostMapping("/{month}/runs/{id}/publish")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> approveReportPublication(@PathVariable String month,
			@PathVariable UUID id,
			@Valid @RequestBody MonthlyReportAdminRequests.Publish request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reports.approveReportPublication(month, id, request.pushAt())));
	}

	@PostMapping("/{month}/runs/{id}/retry")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> retryReportRun(@PathVariable String month,
			@PathVariable UUID id) {
		return ResponseEntity.accepted().body(ApiResponse.ok(reports.retryReportRun(month, id)));
	}
}
