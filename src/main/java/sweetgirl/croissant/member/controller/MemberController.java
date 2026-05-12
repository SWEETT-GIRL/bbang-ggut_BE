package sweetgirl.croissant.member.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.MenuCategoryResponse;
import sweetgirl.croissant.member.service.MemberService;
import sweetgirl.croissant.menu.entity.MenuCategory;

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

	@Operation(summary = "좋아하는 빵 종류 조회", description = "로그인한 유저의 즐겨찾는 빵 카테고리 목록을 반환합니다.")
	@GetMapping("/me/favorite-bread")
	public ApiResponse<MenuCategoryResponse> getLikeMenuCategory(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberService.getLikedMenu(memberId));
	}

	@Operation(summary = "좋아하는 빵 종류 등록", description = "로그인한 유저의 즐겨찾는 빵 카테고리 목록을 업데이트합니다."
		+ " body에 빵 종류 배열을 담아 보내주세요. 예: [\"소금빵\", \"크루아상\", \"베이글\"]")
	@PostMapping("/me/favorite-bread")
	public ApiResponse<Void> enrollLikeMenuCategory(@AuthenticationPrincipal Long memberId,
			@RequestBody String[] bread) {
		memberService.updateLikeMenuCategory(memberId, bread);
		return ApiResponse.ok(null);
	}

	@GetMapping("/me/notification-days")
	public ApiResponse<DayOfWeekResponse> getNotificationDays(@AuthenticationPrincipal Long memberId) {
		DayOfWeekResponse result = memberService.getNotificationDays(memberId);
		return ApiResponse.ok(result);
	}

	@PostMapping("/me/notification-days")
	public ApiResponse<Void> enrollNotificationDays(@AuthenticationPrincipal Long memberId,
			@RequestBody String dayList) {
		memberService.updateNotificationDays(memberId, dayList);
		return ApiResponse.ok(null);
	}

}
