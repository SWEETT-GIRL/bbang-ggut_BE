package sweetgirl.croissant.member.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.MenuCategoryResponse;
import sweetgirl.croissant.member.service.MemberService;
import sweetgirl.croissant.menu.entity.MenuCategory;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@Operation(summary = "내 찜 매장 수 조회", description = "로그인한 사용자가 알람 받는 중인 매장의 개수를 조회합니다.")
	@GetMapping("/me/store-count")
	public ApiResponse<Long> getStoreCount(@AuthenticationPrincipal Long memberId) {
		long count = memberService.getSubscribedStoreCount(memberId);
		return ApiResponse.ok(count);
	}

	@Operation(summary = "좋아하는 빵 종류 조회", description = "로그인한 유저의 즐겨찾는 빵 카테고리 목록을 반환합니다.")
	@GetMapping("/me/favorite-bread")
	public ApiResponse<MenuCategoryResponse> getFavoriteBread(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberService.getLikedMenu(memberId));
	}

	@Operation(summary = "좋아하는 빵 종류 등록", description = "로그인한 유저의 즐겨찾는 빵 카테고리 목록을 업데이트합니다."
		+ " body에 빵 종류 배열을 담아 보내주세요. 예: [\"소금빵\", \"크루아상\", \"베이글\"]")
	@PostMapping("/me/favorite-bread")
	public ApiResponse<Void> enrollFavoriteBread(@AuthenticationPrincipal Long memberId, @RequestBody String[] bread) {
		memberService.updateLikedMenu(memberId, bread);
		return ApiResponse.ok(null);
	}

}
