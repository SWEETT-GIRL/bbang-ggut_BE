package sweetgirl.croissant.alarm.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.dto.request.LocationCheckRequest;
import sweetgirl.croissant.alarm.dto.response.LocationCheckResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.service.AlarmService;
import sweetgirl.croissant.alarm.service.FcmService;
import sweetgirl.croissant.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/alarm")
public class AlarmController {

	private final FcmService fcmService;
	private final AlarmService alarmService;

	@PostMapping("/fcm-token")
	public ApiResponse<Void> registerFcmToken(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid FcmTokenRequest request) {
		fcmService.saveOrUpdateFcmToken(memberId, request);
		return ApiResponse.ok(null);
	}

	// 알람 히스토리 조회
	@GetMapping("/history")
	public ApiResponse<List<StoreNotificationResponse>> getNotificationHistory(@AuthenticationPrincipal Long memberId) {
		List<StoreNotificationResponse> history = alarmService.getStoreNotificationHistory(memberId);
		return ApiResponse.ok(history);
	}

	//알람 읽음 처리
	@PostMapping("/{id}")
	public ApiResponse<Void> readNotification(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		alarmService.markAsRead(memberId, id);
		return ApiResponse.ok(null);
	}

	// 위치 기반 근접 체크 - 구독 빵집 근처 진입 시 FCM 알림 발송
	@PostMapping("/location-check")
	public ApiResponse<LocationCheckResponse> checkLocation(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid LocationCheckRequest request) {
		LocationCheckResponse response = alarmService.checkProximity(memberId, request);
		return ApiResponse.ok(response);
	}
}
