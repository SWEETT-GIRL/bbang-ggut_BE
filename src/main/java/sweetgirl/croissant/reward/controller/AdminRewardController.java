package sweetgirl.croissant.reward.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.request.AdminBbangdaengLetterRequest;
import sweetgirl.croissant.reward.dto.request.AdminCrumbAdjustRequest;
import sweetgirl.croissant.reward.dto.request.AdminRewardSettingsUpdateRequest;
import sweetgirl.croissant.reward.dto.response.AdminBbangdaengLetterResponse;
import sweetgirl.croissant.reward.dto.response.AdminCrumbAdjustResponse;
import sweetgirl.croissant.reward.dto.response.AdminRewardSettingResponse;
import sweetgirl.croissant.reward.service.AdminRewardCommandService;
import sweetgirl.croissant.reward.service.RewardSettings;

/**
 * 재화를 주고 빼는 경로다. {@code /api/admin/**} 는 permitAll 이라 {@code AdminApiKeyFilter} 가 유일한 문이다 —
 * 경로를 옮기면 필터의 {@code PROTECTED_PREFIXES} 와 {@code AdminApiKeyConfig} 의 URL 패턴도 같이 옮긴다.
 */
@Tag(name = "[Admin] 리워드", description = "부스러기 지급·회수와 보상 수치")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/rewards")
public class AdminRewardController {

	private final AdminRewardCommandService adminRewardCommandService;
	private final RewardSettings rewardSettings;

	@Operation(summary = "부스러기 지급", description = "사유를 남기고 준다. 상한·제재와 무관하게 들어간다.")
	@PostMapping("/members/{memberId}/grant")
	public ResponseEntity<ApiResponse<AdminCrumbAdjustResponse>> grant(
			@PathVariable Long memberId,
			@Valid @RequestBody AdminCrumbAdjustRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.grant(memberId, request)));
	}

	@Operation(summary = "부스러기 회수", description = "잔액보다 크게 요청해도 0 까지만 뺀다.")
	@PostMapping("/members/{memberId}/revoke")
	public ResponseEntity<ApiResponse<AdminCrumbAdjustResponse>> revoke(
			@PathVariable Long memberId,
			@Valid @RequestBody AdminCrumbAdjustRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.revoke(memberId, request)));
	}

	@Operation(summary = "빵댕이 편지 글 목록")
	@GetMapping("/letters")
	public ResponseEntity<ApiResponse<List<AdminBbangdaengLetterResponse>>> getLetters() {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.getLetters()));
	}

	@Operation(summary = "빵댕이 편지 글 추가", description = "묶음의 끝에 붙는다.")
	@PostMapping("/letters")
	public ResponseEntity<ApiResponse<AdminBbangdaengLetterResponse>> addLetter(
			@Valid @RequestBody AdminBbangdaengLetterRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.addLetter(request)));
	}

	@Operation(summary = "빵댕이 편지 글 수정", description = "active: false 로 끄면 새로 배달하지 않는다.")
	@PutMapping("/letters/{letterId}")
	public ResponseEntity<ApiResponse<AdminBbangdaengLetterResponse>> reviseLetter(
			@PathVariable Long letterId,
			@Valid @RequestBody AdminBbangdaengLetterRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.reviseLetter(letterId, request)));
	}

	@Operation(summary = "보상 수치 조회", description = "지금 쓰이는 값과 기본값을 내린다.")
	@GetMapping("/settings")
	public ResponseEntity<ApiResponse<List<AdminRewardSettingResponse>>> getSettings() {
		List<AdminRewardSettingResponse> settings = rewardSettings.all().entrySet().stream()
			.map(entry -> new AdminRewardSettingResponse(
				entry.getKey().getKey(), entry.getValue(), entry.getKey().getDefaultValue()))
			.toList();
		return ResponseEntity.ok(ApiResponse.ok(settings));
	}

	@Operation(summary = "보상 수치 변경", description = "보낸 키만 바뀐다. 다른 인스턴스에는 30 초 안에 반영된다.")
	@PutMapping("/settings")
	public ResponseEntity<ApiResponse<Void>> updateSettings(
			@Valid @RequestBody AdminRewardSettingsUpdateRequest request) {
		adminRewardCommandService.updateSettings(request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
