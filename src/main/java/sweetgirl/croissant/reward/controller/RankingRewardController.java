package sweetgirl.croissant.reward.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.response.RankingRewardResponse;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.service.RankingRewardService;

@Tag(name = "[Client] 랭킹 주간 보상", description = "빵순이 랭킹 전국 주간 순위 1~10 위 보상")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/ranking-rewards")
public class RankingRewardController {

	private final RankingRewardService rankingRewardService;

	@Operation(summary = "받을 수 있는 랭킹 주간 보상", description = "아직 안 받았고 기한(4 주)이 남은 것만 최근 주부터 내린다.")
	@GetMapping("/pending")
	public ResponseEntity<ApiResponse<List<RankingRewardResponse>>> getPending(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(rankingRewardService.getPending(memberId)));
	}

	@Operation(summary = "랭킹 주간 보상 받기", description = "이미 받은 것을 다시 누르면 alreadyDone: true 로 답한다. "
		+ "기한이 지났으면 REWARD410_1.")
	@PostMapping("/{rewardId}/claim")
	public ResponseEntity<ApiResponse<RewardClaimResponse>> claim(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long rewardId) {
		return ResponseEntity.ok(ApiResponse.ok(rankingRewardService.claim(memberId, rewardId)));
	}
}
