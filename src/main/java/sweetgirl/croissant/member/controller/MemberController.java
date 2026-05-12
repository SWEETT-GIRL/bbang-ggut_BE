package sweetgirl.croissant.member.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.MenuCategoryResponse;
import sweetgirl.croissant.member.dto.NotificationRadiusRequest;
import sweetgirl.croissant.member.dto.NotificationRadiusResponse;
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

	@Operation(summary = "알림 반경 조회", description = "현재 설정된 알림 반경(m)을 반환합니다.")
	@GetMapping("/me/notification-radius")
	public ApiResponse<NotificationRadiusResponse> getNotificationRadius(
			@AuthenticationPrincipal Long memberId) {
		System.out.println("ㅇㅇㅇㅇ");
		return ApiResponse.ok(memberService.getNotificationRadius(memberId));
	}

	@Operation(summary = "알림 반경 변경", description = "반경을 한 단계 증가하거나 감소합니다. 단계: 100 → 300 → 500 → 700 → 1000 (m)\n\n"
		+ "**Request Body 예시**\n"
		+ "```json\n"
		+ "{ \"direction\": \"UP\" }   // 한 단계 증가\n"
		+ "{ \"direction\": \"DOWN\" } // 한 단계 감소\n"
		+ "```\n\n"
		+ "이미 최솟값(100m)에서 DOWN 요청 시 400, 최댓값(1000m)에서 UP 요청 시 400 반환")
	@PatchMapping("/me/notification-radius")
	public ApiResponse<NotificationRadiusResponse> updateNotificationRadius(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid NotificationRadiusRequest request) {
		return ApiResponse.ok(memberService.updateNotificationRadius(memberId, request));
	}

}
