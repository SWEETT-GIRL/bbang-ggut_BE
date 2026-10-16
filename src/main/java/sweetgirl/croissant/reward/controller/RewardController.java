package sweetgirl.croissant.reward.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.request.PendingRewardAckRequest;
import sweetgirl.croissant.reward.dto.response.AttendanceResponse;
import sweetgirl.croissant.reward.dto.response.BbangdaengLetterOpenResponse;
import sweetgirl.croissant.reward.dto.response.BbangdaengLetterResponse;
import sweetgirl.croissant.reward.dto.response.FeedResponse;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.dto.response.RewardHistoryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.service.BbangdaengLetterService;
import sweetgirl.croissant.reward.service.RewardCommandService;
import sweetgirl.croissant.reward.service.RewardDailyCommandService;
import sweetgirl.croissant.reward.service.RewardQueryService;

@Tag(name = "[Client] 리워드", description = "부스러기 잔액 · 받은 내역 · 환영 선물 · 출석 · 밥 룰렛")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/rewards")
public class RewardController {

	private final RewardCommandService rewardCommandService;
	private final RewardQueryService rewardQueryService;
	private final RewardDailyCommandService rewardDailyCommandService;
	private final BbangdaengLetterService bbangdaengLetterService;

	@Operation(summary = "리워드 요약", description = "잔액 · 오늘 받은 양 · 보상 수치 · 아직 연출하지 못한 보상을 내린다. "
		+ "1.x 에서 넘어온 회원은 첫 조회에서 이관 선물을 받고 그 한 번만 carryOver 가 내려간다. 게스트는 빈 요약이다.")
	@GetMapping("/summary")
	public ResponseEntity<ApiResponse<RewardSummaryResponse>> getSummary(
			@AuthenticationPrincipal Long memberId) {
		CarryOver carryOver = rewardCommandService.carryOverIfNeeded(memberId).orElse(null);
		bbangdaengLetterService.deliverIfDue(memberId);
		return ResponseEntity.ok(ApiResponse.ok(rewardQueryService.getSummary(memberId, carryOver)));
	}

	@Operation(summary = "받은 내역", description = "최신순. nextCursor 를 cursor 로 넘기면 다음 장이다.")
	@GetMapping("/history")
	public ResponseEntity<ApiResponse<RewardHistoryResponse>> getHistory(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "30") int size) {
		return ResponseEntity.ok(ApiResponse.ok(rewardQueryService.getHistory(memberId, cursor, size)));
	}

	@Operation(summary = "환영 선물 받기", description = "2.0 에서 새로 온 회원이 한 번 받는다. "
		+ "이미 받았거나 대상이 아니면 alreadyDone: true 로 답한다(에러가 아니다).")
	@PostMapping("/welcome")
	public ResponseEntity<ApiResponse<RewardClaimResponse>> claimWelcome(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(rewardCommandService.claimWelcome(memberId)));
	}

	@Operation(summary = "출석", description = "하루에 한 번(한국 날짜). 오늘 이미 했으면 에러가 아니라 "
		+ "alreadyDone: true 와 오늘 결과를 그대로 답한다.")
	@PostMapping("/attendance")
	public ResponseEntity<ApiResponse<AttendanceResponse>> checkIn(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(rewardDailyCommandService.checkIn(memberId)));
	}

	@Operation(summary = "밥 룰렛", description = "하루에 한 번 무료(한국 날짜). 서버가 칸을 뽑는다. 무료 판을 쓴 뒤에는 "
		+ "광고를 끝까지 봐서 열린 판(summary.feed.extraReady)이 있을 때만 한 판 더 돌고, 그것도 없으면 "
		+ "alreadyDone: true 와 오늘 결과를 그대로 답한다.")
	@PostMapping("/feed")
	public ResponseEntity<ApiResponse<FeedResponse>> feed(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(rewardDailyCommandService.feed(memberId)));
	}

	@Operation(summary = "빵댕이 편지 목록", description = "지금 보이는 편지를 최근 것부터 12 통까지 내린다.")
	@GetMapping("/letters")
	public ResponseEntity<ApiResponse<List<BbangdaengLetterResponse>>> getLetters(
			@AuthenticationPrincipal Long memberId) {
		bbangdaengLetterService.deliverIfDue(memberId);
		return ResponseEntity.ok(ApiResponse.ok(bbangdaengLetterService.getLetters(memberId)));
	}

	@Operation(summary = "빵댕이 편지 열기", description = "처음 열 때만 부스러기를 받는다. "
		+ "이미 연 편지면 alreadyOpened: true 로 답한다.")
	@PostMapping("/letters/{letterId}/open")
	public ResponseEntity<ApiResponse<BbangdaengLetterOpenResponse>> openLetter(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long letterId) {
		return ResponseEntity.ok(ApiResponse.ok(bbangdaengLetterService.open(memberId, letterId)));
	}

	@Operation(summary = "연출한 보상 확인", description = "pendingRewards 의 연출을 끝낸 뒤 그 id 들을 보낸다.")
	@PostMapping("/pending/ack")
	public ResponseEntity<ApiResponse<Void>> acknowledgePending(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody PendingRewardAckRequest request) {
		rewardCommandService.acknowledgePending(memberId, request.ids());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
