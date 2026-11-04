package sweetgirl.croissant.follow.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import sweetgirl.croissant.follow.dto.response.FollowRequestCountResponse;
import sweetgirl.croissant.follow.dto.response.FollowRequestResponse;
import sweetgirl.croissant.follow.service.FollowRequestCommandService;
import sweetgirl.croissant.follow.service.FollowRequestQueryService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;

@Tag(name = "[Client] 팔로우 요청", description = "비공개 계정이 받은 팔로우 요청 — 목록 · 수락 · 거절")
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class FollowRequestController {

	private final FollowRequestQueryService followRequestQueryService;
	private final FollowRequestCommandService followRequestCommandService;

	@Operation(summary = "받은 팔로우 요청 목록", description = "최신순. 줄마다 요청한 사람(memberId · nickname · 얼굴) · requestedAt. "
		+ "게스트 403 MEMBER403_GUEST.")
	@GetMapping("/me/follow-requests")
	public ApiResponse<SliceResponse<FollowRequestResponse>> getReceived(
			@AuthenticationPrincipal Long memberId,
			@Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기(최대 50)") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(followRequestQueryService.getReceived(memberId, page, size));
	}

	@Operation(summary = "받은 팔로우 요청 수", description = "마이 탭 배지용.")
	@GetMapping("/me/follow-requests/count")
	public ApiResponse<FollowRequestCountResponse> countReceived(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(new FollowRequestCountResponse(followRequestQueryService.countReceived(memberId)));
	}

	@Operation(summary = "팔로우 요청 수락", description = "요청한 사람이 나를 팔로우하게 된다. 요청이 없으면 404 FOLLOW404_REQUEST.")
	@PostMapping("/{memberId}/follow-requests/accept")
	public ApiResponse<Void> accept(
			@AuthenticationPrincipal Long viewerId,
			@Parameter(description = "요청한 사람") @PathVariable Long memberId) {
		followRequestCommandService.accept(viewerId, memberId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "팔로우 요청 거절", description = "요청만 지운다. 요청한 사람에게는 알리지 않는다. 없으면 404 FOLLOW404_REQUEST.")
	@PostMapping("/{memberId}/follow-requests/reject")
	public ApiResponse<Void> reject(
			@AuthenticationPrincipal Long viewerId,
			@Parameter(description = "요청한 사람") @PathVariable Long memberId) {
		followRequestCommandService.reject(viewerId, memberId);
		return ApiResponse.ok(null);
	}
}
