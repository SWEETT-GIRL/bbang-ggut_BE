package sweetgirl.croissant.reward.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.request.AdminBbangdaengLetterRequest;
import sweetgirl.croissant.reward.dto.request.AdminCrumbAdjustRequest;
import sweetgirl.croissant.reward.dto.request.AdminRewardSettingsUpdateRequest;
import sweetgirl.croissant.reward.dto.request.AdminShopItemRequest;
import sweetgirl.croissant.reward.dto.response.AdminBbangdaengLetterResponse;
import sweetgirl.croissant.reward.dto.response.AdminCrumbAdjustResponse;
import sweetgirl.croissant.reward.dto.response.AdminRewardSettingResponse;
import sweetgirl.croissant.reward.dto.response.ShopCatalogResponse;
import sweetgirl.croissant.reward.service.AdminRewardCommandService;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.RankingRewardService;
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
	private final RankingRewardService rankingRewardService;

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

	@Operation(summary = "상점 품목 목록", description = "끈 품목까지 전부 내린다.")
	@GetMapping("/shop/items")
	public ResponseEntity<ApiResponse<List<ShopCatalogResponse.Item>>> getShopItems() {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.getShopItems()));
	}

	@Operation(summary = "상점 품목 만들기·고치기", description = "없던 코드면 만들고 있던 코드면 고친다. "
		+ "active: false 로 끄면 목록에서 빠진다.")
	@PutMapping("/shop/items/{code}")
	public ResponseEntity<ApiResponse<ShopCatalogResponse.Item>> upsertShopItem(
			@PathVariable String code,
			@Valid @RequestBody AdminShopItemRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminRewardCommandService.upsertShopItem(code, request)));
	}

	@Operation(summary = "랭킹 주간 보상 만들기", description = "그 날짜가 든 주의 지난주(월~일) 전국 순위 1~10 위에게 보상 줄을 만든다. "
		+ "월요일 새벽에 자동으로 도는 일을 손으로 다시 돌릴 때 쓴다. 이미 있는 줄은 건드리지 않는다. "
		+ "date 를 안 주면 오늘이다. 지난주 스냅샷이 없으면 -1 을 내린다.")
	@PostMapping("/ranking/issue")
	public ResponseEntity<ApiResponse<Integer>> issueRankingRewards(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		LocalDate today = date != null ? date : LocalDate.now(CrumbGrantService.ZONE);
		return ResponseEntity.ok(ApiResponse.ok(rankingRewardService.issueFor(today)));
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
