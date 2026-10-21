package sweetgirl.dataops.serving.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.serving.dto.response.ApplyLogResponse;
import sweetgirl.dataops.serving.service.ApplyLogQueryService;

@Tag(name = "ApplyLog", description = "운영 DB 쓰기 감사 로그 조회 — 모든 운영 쓰기의 전/후 값 기록")
@RestController
@RequestMapping("/api/apply-logs")
@RequiredArgsConstructor
public class ApplyLogController {

	private final ApplyLogQueryService applyLogQueryService;

	@Operation(summary = "감사 로그 조회", description = "storeId 생략 시 전체를 최신순으로 조회합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<ApplyLogResponse>>> getApplyLogs(
			@RequestParam(required = false) Long storeId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(applyLogQueryService.getApplyLogs(storeId, page, size)));
	}
}
