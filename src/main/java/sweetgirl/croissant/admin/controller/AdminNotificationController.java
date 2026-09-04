package sweetgirl.croissant.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.NoticeSendRequest;
import sweetgirl.croissant.alarm.dto.response.NoticeSendResponse;
import sweetgirl.croissant.alarm.service.NotificationService;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 전 회원에게 공지 알림을 쌓는다.
 *
 * <p>버전 정책(app/version-policy)과 겹치지 않게 쓴다 — 그쪽은 "업데이트 하세요"라며 스토어로
 * 보내는 일이고, 여기는 "이번에 뭐가 생겼는지"를 말하고 그 화면으로 보내는 일이다.
 *
 * <p>이 경로도 /api/admin/** 아래라 SecurityConfig에서 permitAll이다. 편지 발송과 달리 아직
 * 열쇠를 걸어두지 않았다 — 전 회원에게 닿는 API라 편지와 같은 이유로 막아야 한다.
 */
@Tag(name = "Admin Notification", description = "어드민 공지 알림 API")
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

	private final NotificationService notificationService;

	@Operation(summary = "전체 공지 발송", description = "탈퇴하지 않은 전 회원의 알림함에 공지를 한 줄씩 쌓습니다. 푸시는 보내지 않습니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<NoticeSendResponse>> sendNotice(
			@Valid @RequestBody NoticeSendRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(notificationService.sendNotice(request)));
	}
}
