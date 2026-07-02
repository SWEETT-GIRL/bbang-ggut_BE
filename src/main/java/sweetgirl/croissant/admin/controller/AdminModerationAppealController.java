package sweetgirl.croissant.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminModerationAppealReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.admin.service.AdminModerationAppealCommandService;
import sweetgirl.croissant.admin.service.AdminModerationAppealQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Moderation Appeal", description = "어드민 이의제기 관리 API")
@RestController
@RequestMapping("/api/admin/moderation-appeals")
@RequiredArgsConstructor
public class AdminModerationAppealController {

	private final AdminModerationAppealQueryService adminModerationAppealQueryService;
	private final AdminModerationAppealCommandService adminModerationAppealCommandService;

	@Operation(summary = "이의제기 목록 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<Page<AdminModerationAppealResponse>>> getAppeals(
			@Parameter(description = "처리 상태") @RequestParam(required = false) String status,
			@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(adminModerationAppealQueryService.getAppeals(status, page, size)));
	}

	@Operation(summary = "이의제기 승인/거절")
	@PatchMapping("/{appealId}")
	public ResponseEntity<ApiResponse<AdminModerationAppealResponse>> reviewAppeal(
			@Parameter(description = "이의제기 ID") @PathVariable Long appealId,
			@Valid @RequestBody AdminModerationAppealReviewRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminModerationAppealCommandService.reviewAppeal(appealId, request)));
	}
}
