package sweetgirl.croissant.profile.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.profile.dto.response.ProfileResponse;
import sweetgirl.croissant.profile.dto.response.VisitedStoreResponse;
import sweetgirl.croissant.profile.service.MemberSearchQueryService;
import sweetgirl.croissant.profile.service.MemberSuggestionQueryService;
import sweetgirl.croissant.profile.service.ProfileQueryService;
import sweetgirl.croissant.profile.service.VisitedStoreQueryService;

@Tag(name = "[Client] 프로필", description = "공개 프로필 · 빵순이 찾기 · 추천")
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class ProfileController {

	private final ProfileQueryService profileQueryService;
	private final MemberSearchQueryService memberSearchQueryService;
	private final MemberSuggestionQueryService memberSuggestionQueryService;
	private final VisitedStoreQueryService visitedStoreQueryService;

	@Operation(summary = "빵순이 찾기", description = "닉네임 앞글자 일치(공백 · 대소문자 무시), 2자부터, 20명. "
		+ "차단 관계 · 탈퇴 · 게스트 · 프로필 제한 중인 사람은 빠진다.")
	@GetMapping("/search")
	public ApiResponse<List<FollowMemberResponse>> search(
			@AuthenticationPrincipal Long viewerId,
			@RequestParam(name = "q") String query) {
		return ApiResponse.ok(memberSearchQueryService.search(viewerId, query));
	}

	@Operation(summary = "이런 빵순이 어때요", description = "최근 7일 공개 빵로그가 있는 사람 3명, 같은 빵집 기록자 우선. "
		+ "이미 팔로우 중 · 차단 관계는 빠진다. 게스트 403 MEMBER403_GUEST.")
	@GetMapping("/suggestions")
	public ApiResponse<List<FollowMemberResponse>> suggestions(@AuthenticationPrincipal Long viewerId) {
		return ApiResponse.ok(memberSuggestionQueryService.suggest(viewerId));
	}

	@Operation(summary = "프로필", description = "차단 관계거나 프로필 제한 중이면 blocked: true 로 200(나머지 비움). "
		+ "상대가 게스트 · 봇 · 탈퇴면 404 PROFILE404_MEMBER.")
	@GetMapping("/{memberId}/profile")
	public ApiResponse<ProfileResponse> getProfile(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId) {
		return ApiResponse.ok(profileQueryService.getProfile(viewerId, memberId));
	}

	@Operation(summary = "다녀간 빵집", description = "그 사람의 공개 빵로그가 있는 가게를 핀으로(칭호를 누르면 여는 지도). "
		+ "보는 사람에게 보이는 기록만 센다(내가 신고한 것 제외). 차단 관계 403 PROFILE403_BLOCKED, 없음 404 PROFILE404_MEMBER.")
	@GetMapping("/{memberId}/visited-stores")
	public ApiResponse<List<VisitedStoreResponse>> getVisitedStores(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId) {
		return ApiResponse.ok(visitedStoreQueryService.getVisitedStores(viewerId, memberId));
	}
}
