package sweetgirl.croissant.monthlyreport.admin.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportOperationsResponse;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsQueryService;

@Tag(name = "[Admin] 월간 보고서 운영", description = "월간 보고서 운영 API")
@RestController
@RequestMapping("/api/admin/monthly-reports/{month}/runs/{id}")
@RequiredArgsConstructor
public class AdminMonthlyReportOperationsController {
	private final MonthlyReportOperationsQueryService operationsQueryService;
	private final MonthlyReportOperationsCommandService operationsCommandService;

	@GetMapping("/operations")
	public ResponseEntity<ApiResponse<MonthlyReportOperationsResponse>> getReportOperations(
			@PathVariable String month,
			@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok().header("Cache-Control", "no-store")
			.body(ApiResponse.ok(operationsQueryService.getReportOperations(month, id, page, size)));
	}

	@PostMapping("/members/{memberId}/push/retry")
	public ResponseEntity<ApiResponse<Void>> retryFailedReportPush(@PathVariable String month, @PathVariable UUID id,
			@PathVariable Long memberId) {
		operationsCommandService.retryFailedReportPush(month, id, memberId);
		return ResponseEntity.accepted().body(ApiResponse.ok(null));
	}
}
