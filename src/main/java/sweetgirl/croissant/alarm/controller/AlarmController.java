package sweetgirl.croissant.alarm.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.dto.request.GeofenceEnterRequest;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidatesResponse;
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

	@Operation(summary = "Geofence 후보 가게 조회", description = "현재 위치 기준 구독 가게 최대 20개 반환. 앱 OS Geofence 등록용.")
	@GetMapping("/geofence-candidates")
	public ApiResponse<GeofenceCandidatesResponse> getGeofenceCandidates(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "현재 위치 위도") @RequestParam double lat,
			@Parameter(description = "현재 위치 경도") @RequestParam double lng) {
		GeofenceCandidatesResponse geofenceCandidates = alarmService.getGeofenceCandidates(memberId, lat, lng);
		return ApiResponse.ok(geofenceCandidates);
	}

	@Operation(summary = "Geofence 진입 이벤트 처리", description = "OS enter 이벤트 수신 시 서버 재검증 후 FCM 발송.")
	@PostMapping("/geofence/enter")
	public ApiResponse<Void> handleGeofenceEnter(@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid GeofenceEnterRequest request) {
		alarmService.handleGeofenceEnter(memberId, request);
		return ApiResponse.ok(null);
	}

}
