package sweetgirl.croissant.monthlyreport.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;

@Tag(name = "MonthlyReport", description = "월간 빵일지 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/reports/monthly")
public class MonthlyReportController {

	private final MonthlyReportQueryService queryService;

	@Operation(summary = "내 월간 빵일지 조회", description = "YYYY-MM 형식의 발행된 스냅샷을 조회합니다. 미발행은 404입니다.")
	@GetMapping("/{yearMonth}/me")
	public ResponseEntity<ApiResponse<MonthlyReportResponse>> getReport(
			@AuthenticationPrincipal Long memberId, @PathVariable String yearMonth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(queryService.getReport(memberId, yearMonth)));
	}
}
