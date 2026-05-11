package sweetgirl.croissant.alarm.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.dto.request.LocationCheckRequest;
import sweetgirl.croissant.alarm.dto.response.LocationCheckResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.service.AlarmService;
import sweetgirl.croissant.alarm.service.FcmService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Alarm", description = "알림 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/alarm")
public class AlarmController {

	private final FcmService fcmService;
	private final AlarmService alarmService;

	@Operation(summary = "FCM 토큰 등록/갱신", description = "디바이스의 FCM 푸시 토큰을 등록하거나 갱신합니다. 동일 deviceId가 존재하면 토큰을 업데이트합니다.")
	@PostMapping("/fcm-token")
	public ApiResponse<Void> registerFcmToken(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid FcmTokenRequest request) {
		fcmService.saveOrUpdateFcmToken(memberId, request);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "알림 히스토리 조회", description = "로그인한 사용자에게 발송된 빵집 알림 목록을 최신순으로 반환합니다.")
	@GetMapping("/history")
	public ApiResponse<List<StoreNotificationResponse>> getNotificationHistory(@AuthenticationPrincipal Long memberId) {
		List<StoreNotificationResponse> history = alarmService.getStoreNotificationHistory(memberId);
		return ApiResponse.ok(history);
	}

	@Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 상태로 변경합니다.")
	@PostMapping("/{id}")
	public ApiResponse<Void> readNotification(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "읽음 처리할 알림 ID") @PathVariable Long id) {
		alarmService.markAsRead(memberId, id);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "위치 기반 근접 체크", description = "현재 위치 기반으로 구독 빵집 근처 진입 여부를 확인하고, 조건 충족 시 FCM 알림을 발송합니다.")
	@PostMapping("/location-check")
	public ApiResponse<LocationCheckResponse> checkLocation(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid LocationCheckRequest request) {
		LocationCheckResponse response = alarmService.checkProximity(memberId, request);
		return ApiResponse.ok(response);
	}
}
