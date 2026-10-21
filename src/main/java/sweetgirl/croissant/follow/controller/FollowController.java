package sweetgirl.croissant.follow.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.service.FollowCommandService;
import sweetgirl.croissant.follow.service.FollowQueryService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;

@Tag(name = "[Client] 팔로우", description = "팔로우 · 팔로워 · 팔로잉 목록")
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class FollowController {

	private final FollowCommandService followCommandService;
	private final FollowQueryService followQueryService;

	@Operation(summary = "팔로우", description = "한쪽이 누르면 끝이고 수락이 없다. 이미 팔로우 중이면 그대로 200. "
		+ "게스트 403 MEMBER403_GUEST · 정지 403 · 나 자신 400 · 상대 없음 404 · 차단 관계 403 · 하루 100명 넘으면 429.")
	@PostMapping("/{memberId}/follow")
	public ApiResponse<Void> follow(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId) {
		followCommandService.follow(viewerId, memberId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "팔로우 취소", description = "안 하고 있었어도 200.")
	@DeleteMapping("/{memberId}/follow")
	public ApiResponse<Void> unfollow(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId) {
		followCommandService.unfollow(viewerId, memberId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "팔로워 목록", description = "최신순. 보는 사람과 차단 관계인 사람 · 탈퇴한 사람은 빠진다. "
		+ "줄마다 followedByMe(내가 팔로우하나) · followsMe(나를 팔로우하나).")
	@GetMapping("/{memberId}/followers")
	public ApiResponse<SliceResponse<FollowMemberResponse>> getFollowers(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId,
			@Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기(최대 50)") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(followQueryService.getFollowers(viewerId, memberId, page, size));
	}

	@Operation(summary = "팔로잉 목록", description = "조건은 팔로워 목록과 같다.")
	@GetMapping("/{memberId}/followings")
	public ApiResponse<SliceResponse<FollowMemberResponse>> getFollowings(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId,
			@Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기(최대 50)") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(followQueryService.getFollowings(viewerId, memberId, page, size));
	}
}
