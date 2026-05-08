package sweetgirl.croissant.alarm.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
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
	public ApiResponse<Void> registerFcmToken(@RequestBody @Valid FcmTokenRequest request) {
		// TODO: memberId는 인증 구현 후 SecurityContext에서 꺼내기
		fcmService.saveOrUpdateToken(null, request);
		return ApiResponse.ok(null);
	}

	// 알람 히스토리 조회
	@GetMapping("/history")
	public ApiResponse<List<StoreNotificationResponse>> getNotificationHistory() {

		// TODO: 인증 구현 후 SecurityContext에서 member 꺼내기
		List<StoreNotificationResponse> history = alarmService.getStoreNotificationHistory(null);
		return ApiResponse.ok(history);
	}
}
