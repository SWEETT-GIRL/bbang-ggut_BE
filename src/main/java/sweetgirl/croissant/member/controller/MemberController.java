package sweetgirl.croissant.member.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.request.ConsentCreateRequest;
import sweetgirl.croissant.member.dto.request.ConsentUpdateRequest;
import sweetgirl.croissant.member.dto.request.NotificationRadiusRequest;
import sweetgirl.croissant.member.dto.request.NotificationTimeRequest;
import sweetgirl.croissant.member.dto.response.ConsentResponse;
import sweetgirl.croissant.member.dto.response.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.member.dto.response.NotificationRadiusResponse;
import sweetgirl.croissant.member.dto.response.NotificationTimeResponse;
import sweetgirl.croissant.member.service.MemberCommandService;
import sweetgirl.croissant.member.service.MemberQueryService;
import sweetgirl.croissant.menu.entity.MenuCategory;

@Tag(name = "Member", description = "사용자 API")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class MemberController {

	private final MemberQueryService memberQueryService;
	private final MemberCommandService memberCommandService;

	@Operation(summary = "내 프로필 조회", description = "로그인한 사용자의 memberId와 닉네임을 반환합니다.")
	@GetMapping("/me")
	public ApiResponse<MemberProfileResponse> getProfile(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberQueryService.getProfile(memberId));
	}

	@Operation(summary = "구독 빵집 수 조회", description = "로그인한 사용자가 알림 구독 중인 빵집의 총 개수를 반환합니다.")
	@GetMapping("/me/store-count")
	public ApiResponse<Long> getStoreCount(@AuthenticationPrincipal Long memberId) {
		long count = memberQueryService.getSubscribedStoreCount(memberId);
		return ApiResponse.ok(count);
	}

	@Operation(summary = "최초 동의 저장", description = "온보딩 시 약관·마케팅·야간 광고 동의 내용을 저장합니다.")
	@PostMapping("/me/consent")
	public ApiResponse<Void> createConsent(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid ConsentCreateRequest request) {
		memberCommandService.createConsent(memberId, request);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "개인정보 동의 상태 조회", description = "로그인한 사용자의 필수 약관 및 마케팅 수신 동의 상태를 반환합니다.")
	@GetMapping("/me/consent")
	public ApiResponse<ConsentResponse> getConsent(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberQueryService.getConsent(memberId));
	}

	@Operation(summary = "개인정보/마케팅 동의 상태 갱신", description = "선택 동의인 마케팅 수신 동의 상태를 변경합니다.")
	@PatchMapping("/me/consent")
	public ApiResponse<ConsentResponse> updateConsent(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid ConsentUpdateRequest request) {
		return ApiResponse.ok(memberCommandService.updateConsent(memberId, request));
	}

	@Operation(summary = "좋아하는 빵 종류 조회", description = "로그인한 유저의 즐겨찾는 빵 카테고리 목록을 반환합니다.")
	@GetMapping("/me/favorite-bread")
	public ApiResponse<MenuCategoryResponse> getLikeMenuCategory(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberQueryService.getLikedMenu(memberId));
	}

	@Operation(summary = "좋아하는 빵 종류 등록", description = "로그인한 유저의 즐겨찾는 빵 카테고리 목록을 업데이트합니다."
		+ " body에 빵 종류 배열을 담아 보내주세요. 예: [\"소금빵\", \"크루아상\", \"베이글\"]")
	@PostMapping("/me/favorite-bread")
	public ApiResponse<Void> enrollLikeMenuCategory(@AuthenticationPrincipal Long memberId,
			@RequestBody String[] bread) {
		memberCommandService.updateLikeMenuCategory(memberId, bread);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "알림 요일 조회", description = "현재 설정된 알림 수신 요일 목록을 반환합니다.")
	@GetMapping("/me/notification-days")
	public ApiResponse<DayOfWeekResponse> getNotificationDays(@AuthenticationPrincipal Long memberId) {
		DayOfWeekResponse result = memberQueryService.getNotificationDays(memberId);
		return ApiResponse.ok(result);
	}

	@Operation(summary = "알림 요일 등록", description = "알림 수신 요일을 설정합니다. body에 요일 문자열을 담아 보내주세요.")
	@PostMapping("/me/notification-days")
	public ApiResponse<Void> enrollNotificationDays(@AuthenticationPrincipal Long memberId,
			@RequestBody String dayList) {
		memberCommandService.updateNotificationDays(memberId, dayList);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "알림 반경 조회", description = "현재 설정된 알림 반경(m)을 반환합니다.")
	@GetMapping("/me/notification-radius")
	public ApiResponse<NotificationRadiusResponse> getNotificationRadius(
			@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberQueryService.getNotificationRadius(memberId));
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
		return ApiResponse.ok(memberCommandService.updateNotificationRadius(memberId, request));
	}

	@Operation(summary = "알림 시간대 조회", description = "현재 설정된 알림 시작/종료 시간을 반환합니다. (HH:mm 형식)")
	@GetMapping("/me/notification-time")
	public ApiResponse<NotificationTimeResponse> getNotificationTime(
			@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(memberQueryService.getNotificationTime(memberId));
	}

	@Operation(summary = "알림 시간대 수정", description = "알림 시작/종료 시간을 수정합니다. (HH:mm 형식, 시작 시간은 종료 시간보다 이전이어야 합니다.)")
	@PatchMapping("/me/notification-time")
	public ApiResponse<NotificationTimeResponse> updateNotificationTime(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid NotificationTimeRequest request) {
		return ApiResponse.ok(memberCommandService.updateNotificationTime(memberId, request));
	}

	@Operation(summary = "회원 탈퇴", description = "로그인한 사용자의 계정을 탈퇴 처리합니다. 탈퇴 시 모든 RefreshToken이 revoke되고 FCM 토큰이 삭제됩니다.")
	// TODO: 인증 완료 후 SecurityContext에서 memberId 주입
	@DeleteMapping("/me")
	public ApiResponse<Void> deleteMember(@AuthenticationPrincipal Long memberId) {
		memberCommandService.deleteMember(memberId); // soft delete 수행
		return ApiResponse.ok(null); // 응답 바디 없이 200 반환
	}

}
