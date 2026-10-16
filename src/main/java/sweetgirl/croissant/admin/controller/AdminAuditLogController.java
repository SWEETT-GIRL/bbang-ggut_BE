package sweetgirl.croissant.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminAuditLogResponse;
import sweetgirl.croissant.admin.service.AdminAuditLogQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

	private final AdminAuditLogQueryService adminAuditLogQueryService;

	@Operation(summary = "어드민 감사 로그 조회", description = "어드민 쓰기 요청 기록을 최신순으로 봅니다. 대상 종류·id 로 거를 수 있습니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<AdminAuditLogResponse.PageResponse>> search(
			@Parameter(description = "MEMBER·COURSE 등") @RequestParam(required = false) String targetType,
			@Parameter(description = "대상 id") @RequestParam(required = false) String targetId,
			@Parameter(description = "0부터") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "기본 20, 최대 100") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(adminAuditLogQueryService.search(targetType, targetId, page, size)));
	}
}
