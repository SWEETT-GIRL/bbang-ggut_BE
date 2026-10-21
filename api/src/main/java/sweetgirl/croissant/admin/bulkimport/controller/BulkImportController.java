package sweetgirl.croissant.admin.bulkimport.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.code.BulkImportErrorCode;
import sweetgirl.croissant.admin.bulkimport.dto.response.BulkImportProgressResponse;
import sweetgirl.croissant.admin.bulkimport.dto.response.BulkImportStartResponse;
import sweetgirl.croissant.admin.bulkimport.dto.response.FailedDongResponse;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestrator;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressService;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Bulk Import", description = "전국 빵집/카페 Kakao bulk import 관리 API")
@RestController
@RequestMapping("/api/admin/stores/bulk-import")
@RequiredArgsConstructor
public class BulkImportController {

	private final BulkImportOrchestrator orchestrator;
	private final BulkImportProgressService progressService;

	@Operation(summary = "Bulk import 시작", description = "전국 동 단위로 Kakao Local API에서 빵집/카페를 수집해 dev DB에 적재합니다. "
		+ "이미 실행 중이면 409를 반환합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<BulkImportStartResponse>> start() {
		if (!orchestrator.tryStart()) {
			throw new CustomException(BulkImportErrorCode.BULK_IMPORT_ALREADY_RUNNING);
		}
		orchestrator.runImportAsync();
		return ResponseEntity.ok(ApiResponse.ok(new BulkImportStartResponse(
			progressService.countTotal(),
			progressService.countByStatus(BulkImportStatus.PENDING),
			progressService.countByStatus(BulkImportStatus.COMPLETED))));
	}

	@Operation(summary = "Bulk import 진행상황 조회")
	@GetMapping("/progress")
	public ResponseEntity<ApiResponse<BulkImportProgressResponse>> getProgress() {
		return ResponseEntity.ok(ApiResponse.ok(new BulkImportProgressResponse(
			orchestrator.isRunning(),
			progressService.countTotal(),
			progressService.countByStatus(BulkImportStatus.COMPLETED),
			progressService.countByStatus(BulkImportStatus.FAILED),
			progressService.countByStatus(BulkImportStatus.IN_PROGRESS),
			progressService.countByStatus(BulkImportStatus.PENDING),
			progressService.sumInsertedCount())));
	}

	@Operation(summary = "실패한 동 목록 조회")
	@GetMapping("/failed")
	public ResponseEntity<ApiResponse<List<FailedDongResponse>>> getFailed() {
		List<FailedDongResponse> data = progressService.getFailed().stream()
			.map(FailedDongResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.ok(data));
	}

	@Operation(summary = "Bulk import 중단 요청", description = "현재 동 처리를 마치고 종료합니다. 다음 호출 시 재개 가능합니다.")
	@PostMapping("/stop")
	public ResponseEntity<ApiResponse<Void>> stop() {
		orchestrator.requestStop();
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
