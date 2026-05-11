package sweetgirl.croissant.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.service.MemberService;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@GetMapping("/me/store-count")
	public ApiResponse<Long> getStoreCount(@AuthenticationPrincipal Long memberId) {
		long count = memberService.getSubscribedStoreCount(memberId);
		return ApiResponse.ok(count);
	}

}
