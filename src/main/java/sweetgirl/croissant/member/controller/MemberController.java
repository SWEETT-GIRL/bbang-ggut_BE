package sweetgirl.croissant.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.service.MemberService;

@Tag(name = "Member", description = "사용자 API")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@Operation(summary = "구독 빵집 수 조회", description = "로그인한 사용자가 알림 구독 중인 빵집의 총 개수를 반환합니다.")
	@GetMapping("/me/store-count")
	public ApiResponse<Long> getStoreCount(@AuthenticationPrincipal Long memberId) {
		long count = memberService.getSubscribedStoreCount(memberId);
		return ApiResponse.ok(count);
	}

}
