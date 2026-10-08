package sweetgirl.croissant.breadlog.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordActivityResponse;
import sweetgirl.croissant.breadlog.service.BreadRecordActivityQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "[Client] BreadLog", description = "빵 기록 활동 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-records/activity")
public class BreadRecordActivityController {

	private final BreadRecordActivityQueryService queryService;

	@Operation(summary = "내 기록 스트릭 조회", description = "게스트도 조회할 수 있습니다. "
		+ "KST 작성일 기준으로 삭제되지 않은 튜토리얼·일반 기록을 집계하며 먹은 날과는 구분합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<BreadRecordActivityResponse>> getActivity(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(queryService.getActivity(memberId)));
	}
}
