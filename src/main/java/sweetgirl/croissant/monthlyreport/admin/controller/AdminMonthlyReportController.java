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
	public ApiResponse<MonthlyReportAssetStore.Upload> upload(
			@Valid @RequestBody MonthlyReportAdminRequests.Asset request) {
		return ApiResponse.ok(assets.upload(request));
	}

	@PostMapping("/assets")
	public ApiResponse<MonthlyReportAsset> register(@Valid @RequestBody MonthlyReportAdminRequests.Asset request) {
		return ApiResponse.ok(assets.register(request));
	}

	@GetMapping("/assets")
	public ApiResponse<List<MonthlyReportAsset>> assets(@RequestParam String version) {
		return ApiResponse.ok(assets.list(version));
	}

	@PostMapping("/{month}/runs")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> create(@PathVariable String month,
			@Valid @RequestBody MonthlyReportAdminRequests.CreateRun request) {
		return ResponseEntity.accepted()
			.body(ApiResponse.ok(reports.create(month, request.assetVersion())));
	}

	@PostMapping("/{month}/members/{memberId}/runs")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> createForMember(@PathVariable String month,
			@PathVariable Long memberId, @Valid @RequestBody MonthlyReportAdminRequests.CreateRun request) {
		return ResponseEntity.accepted().body(ApiResponse.ok(reports.create(month, request.assetVersion(), memberId)));
	}

	@GetMapping("/{month}/runs/{id}")
	public ApiResponse<MonthlyReportAdminService.Progress> progress(@PathVariable String month, @PathVariable UUID id) {
		return ApiResponse.ok(reports.progress(month, id));
	}

	@GetMapping("/{month}/runs/{id}/members")
	public ApiResponse<SliceResponse<MonthlyReportAdminService.Item>> items(@PathVariable String month,
			@PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(reports.items(month, id, page, size));
	}

	@GetMapping("/{month}/runs/{id}/preview/{memberId}")
	public ResponseEntity<ApiResponse<MonthlyReportResponse>> preview(@PathVariable String month, @PathVariable UUID id,
			@PathVariable Long memberId) {
		return ResponseEntity.ok().header("Cache-Control", "no-store")
			.body(ApiResponse.ok(reports.preview(month, id, memberId)));
	}

	@PostMapping("/{month}/runs/{id}/publish")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> publish(@PathVariable String month, @PathVariable UUID id,
			@Valid @RequestBody MonthlyReportAdminRequests.Publish request) {
		return ResponseEntity.accepted().body(ApiResponse.ok(reports.approve(month, id, request.pushAt())));
	}

	@PostMapping("/{month}/runs/{id}/retry")
	public ResponseEntity<ApiResponse<MonthlyReportRun>> retry(@PathVariable String month, @PathVariable UUID id) {
		return ResponseEntity.accepted().body(ApiResponse.ok(reports.retry(month, id)));
	}
}
