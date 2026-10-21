package sweetgirl.croissant.breadlog.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordResponse;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;

@Tag(name = "BreadLog", description = "빵 기록 API")
@RestController
@RequiredArgsConstructor
public class StoreBreadRecordController {

	private final BreadRecordQueryService queryService;

	@Operation(summary = "가게 공개 빵로그 목록", description = "그 가게에 공개된 빵 기록을 먹은 날 최신순으로 반환합니다. "
		+ "비로그인도 조회할 수 있고, 로그인했으면 내가 차단한 작성자의 기록은 뺍니다.")
	@GetMapping("/stores/{storeId}/bread-records")
	public ResponseEntity<ApiResponse<SliceResponse<StoreBreadRecordResponse>>> getStorePublicRecords(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(queryService.getStorePublicRecords(storeId, memberId, page, size)));
	}
}
