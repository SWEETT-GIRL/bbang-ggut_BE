package sweetgirl.croissant.alarm.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.request.FcmTokenDeleteRequest;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.dto.request.GeofenceEnterRequest;
import sweetgirl.croissant.alarm.dto.response.CourseSaveNotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidatesResponse;
import sweetgirl.croissant.alarm.dto.response.MarketingEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.ReportNotificationResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.dto.response.UnreadCountResponse;
import sweetgirl.croissant.alarm.service.AlarmCommandService;
import sweetgirl.croissant.alarm.service.AlarmQueryService;
import sweetgirl.croissant.alarm.service.CourseSaveNotificationService;
import sweetgirl.croissant.alarm.service.FcmService;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Alarm", description = "알림 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/alarm")
public class AlarmController {

	private final FcmService fcmService;
	private final AlarmQueryService alarmQueryService;
	private final AlarmCommandService alarmCommandService;
	private final ReportNotificationService reportNotificationService;
	private final CourseSaveNotificationService courseSaveNotificationService;

	@Operation(summary = "FCM 토큰 등록/갱신", description = "디바이스의 FCM 푸시 토큰을 등록하거나 갱신합니다. 동일 deviceId가 존재하면 토큰을 업데이트합니다.")
	@PostMapping("/fcm-token")
	public ApiResponse<Void> registerFcmToken(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid FcmTokenRequest request) {
		fcmService.saveOrUpdateFcmToken(memberId, request);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "FCM 토큰 비활성화", description = "디바이스의 FCM 푸시 토큰을 비활성화합니다. 로그아웃 시 호출.")
	@DeleteMapping("/fcm-token")
	public ApiResponse<Void> deactivateFcmToken(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid FcmTokenDeleteRequest request) {
		fcmService.deactivateFcmToken(request.getToken());
		return ApiResponse.ok(null);
	}

	@Operation(summary = "알림 히스토리 조회", description = "로그인한 사용자에게 발송된 빵집 알림 목록을 최신순으로 반환합니다.")
	@GetMapping("/history")
	public ApiResponse<List<StoreNotificationResponse>> getNotificationHistory(@AuthenticationPrincipal Long memberId) {
		List<StoreNotificationResponse> history = alarmQueryService.getStoreNotificationHistory(memberId);
		return ApiResponse.ok(history);
	}

	@Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 상태로 변경합니다.")
	@PostMapping("/{id}")
	public ApiResponse<Void> readNotification(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "읽음 처리할 알림 ID") @PathVariable Long id) {
		alarmCommandService.markAsRead(memberId, id);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "빵알람 수신 여부 조회", description = "현재 빵알람 수신 여부를 반환합니다.")
	@GetMapping("/settings/notification") // GET /alarm/settings/notification
	public ApiResponse<NotificationEnabledResponse> getNotificationEnabled(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(alarmQueryService.getNotificationEnabled(memberId)); // 서비스에서 현재 상태 조회 후 응답
	}

	@Operation(summary = "빵알람 수신 여부 토글", description = "빵알람 수신 여부를 반전시키고 변경된 상태를 반환합니다.")
	@PatchMapping("/settings/notification") // PATCH /alarm/settings/notification — request body 없이 호출
	public ApiResponse<NotificationEnabledResponse> toggleNotificationEnabled(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(alarmCommandService.toggleNotificationEnabled(memberId)); // 토글 후 변경된 상태 응답
	}

	@Operation(summary = "마케팅 알람 수신 여부 조회", description = "현재 마케팅 알람 수신 여부를 반환합니다.")
	@GetMapping("/settings/marketing") // GET /alarm/settings/marketing
	public ApiResponse<MarketingEnabledResponse> getMarketingEnabled(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(alarmQueryService.getMarketingEnabled(memberId)); // 서비스에서 현재 상태 조회 후 응답
	}

	@Operation(summary = "마케팅 알람 수신 여부 토글", description = "마케팅 알람 수신 여부를 반전시키고 변경된 상태를 반환합니다.")
	@PatchMapping("/settings/marketing") // PATCH /alarm/settings/marketing — request body 없이 호출
	public ApiResponse<MarketingEnabledResponse> toggleMarketingEnabled(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(alarmCommandService.toggleMarketingEnabled(memberId)); // 토글 후 변경된 상태 응답
	}

	@Operation(summary = "내 제보 알림 목록 조회", description = "사용자가 제출한 제보의 승인/거절 알림 목록을 최신순으로 반환합니다.")
	@GetMapping("/report-notifications")
	public ApiResponse<List<ReportNotificationResponse>> getMyReportNotifications(
			@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(reportNotificationService.getMyReportNotifications(memberId));
	}

	@Operation(summary = "제보 알림 읽음 처리", description = "특정 제보 알림을 읽음 상태로 변경합니다.")
	@PatchMapping("/report-notifications/{id}/read")
	public ApiResponse<Void> readReportNotification(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "제보 알림 ID") @PathVariable Long id) {
		reportNotificationService.markAsRead(memberId, id);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "내 빵투어 찜 알림 목록 조회", description = "다른 사용자가 내 빵투어를 찜한 알림을 최신순으로 반환합니다.")
	@GetMapping("/course-notifications")
	public ApiResponse<CourseSaveNotificationListResponse> getMyCourseSaveNotifications(
			@AuthenticationPrincipal Long memberId,
			@Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기(최대 50)") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(courseSaveNotificationService.getMyNotifications(memberId, page, size));
	}

	@Operation(summary = "내 빵투어 찜 알림 미읽음 개수", description = "상단바 알림 배지 표시용. 읽지 않은 알림 개수를 반환합니다.")
	@GetMapping("/course-notifications/unread-count")
	public ApiResponse<UnreadCountResponse> getCourseSaveNotificationUnreadCount(
			@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(courseSaveNotificationService.getUnreadCount(memberId));
	}

	@Operation(summary = "내 빵투어 찜 알림 읽음 처리", description = "notificationIds를 주면 해당 알림만, 없거나 비어 있으면 전체를 읽음 처리합니다.")
	@PatchMapping("/course-notifications/read")
	public ApiResponse<Void> readCourseSaveNotifications(@AuthenticationPrincipal Long memberId,
			@RequestBody(required = false) CourseNotificationReadRequest request) {
		courseSaveNotificationService.markAsRead(memberId, request);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "Geofence 후보 가게 조회", description = "현재 위치 기준 구독 가게 최대 20개 반환. 앱 OS Geofence 등록용.")
	@GetMapping("/geofence-candidates")
	public ApiResponse<GeofenceCandidatesResponse> getGeofenceCandidates(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "현재 위치 위도") @RequestParam double lat,
			@Parameter(description = "현재 위치 경도") @RequestParam double lng) {
		GeofenceCandidatesResponse geofenceCandidates = alarmQueryService.getGeofenceCandidates(memberId, lat, lng);
		return ApiResponse.ok(geofenceCandidates);
	}

	@Operation(summary = "Geofence 진입 이벤트 처리", description = "OS enter 이벤트 수신 시 서버 재검증 후 FCM 발송.")
	@PostMapping("/geofence/enter")
	public ApiResponse<Void> handleGeofenceEnter(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid GeofenceEnterRequest request) {
		alarmCommandService.handleGeofenceEnter(memberId, request);
		return ApiResponse.ok(null);
	}

}
