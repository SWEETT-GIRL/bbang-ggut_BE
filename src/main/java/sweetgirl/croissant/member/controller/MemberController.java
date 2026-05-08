package sweetgirl.croissant.member.controller;

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
	public ApiResponse<Long> getStoreCount() {
		// TODO: 인증 구현 후 SecurityContext에서 Member 꺼내기
		long count = memberService.getSubscribedStoreCount(null);
		return ApiResponse.ok(count);
	}

}
