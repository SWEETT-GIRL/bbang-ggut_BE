package sweetgirl.croissant.alarm.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.alarm.dto.response.NotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationResponse.ActorResponse;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.service.AlarmCommandService;
import sweetgirl.croissant.alarm.service.AlarmQueryService;
import sweetgirl.croissant.alarm.service.FcmService;
import sweetgirl.croissant.alarm.service.NotificationService;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;

/**
 * 알림함 응답 모양을 고정한다. 기존 필드(actorId · actorNickname · targetType · targetId · title · body · link)는
 * 설치된 앱이 읽어 그대로 두고, 2026-10-22 에 group · actors · actorCount 를 더했다(FE F6 알림함 묶음).
 */
@ContextConfiguration(classes = AlarmController.class)
class NotificationResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private FcmService fcmService;
	@MockitoBean
	private AlarmQueryService alarmQueryService;
	@MockitoBean
	private AlarmCommandService alarmCommandService;
	@MockitoBean
	private NotificationService notificationService;
	@MockitoBean
	private ReportNotificationService reportNotificationService;

	@Test
	void notifications_keepsFieldsTheAppReads() throws Exception {
		given(notificationService.getMine(any(), anyInt(), anyInt())).willReturn(new NotificationListResponse(List.of(
			new NotificationResponse(812L, NotificationType.RECORD_LIKE, false, LocalDateTime.of(2026, 10, 22, 10, 0),
				2L, "빵순이", NotificationTargetType.RECORD, 10L, "밀도", "빵순이님이 회원님의 빵로그를 좋아해요", null,
				"/bbanglog/post/10", "TODAY", List.of(new ActorResponse(2L, "빵순이", "dog")), 3)),
			false));

		mockMvc.perform(get("/alarm/notifications"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notifications[0].notificationId").isNumber())
			.andExpect(jsonPath("$.data.notifications[0].type").isString())
			.andExpect(jsonPath("$.data.notifications[0].read").isBoolean())
			.andExpect(jsonPath("$.data.notifications[0].actorId").isNumber())
			.andExpect(jsonPath("$.data.notifications[0].actorNickname").isString())
			.andExpect(jsonPath("$.data.notifications[0].targetType").isString())
			.andExpect(jsonPath("$.data.notifications[0].targetId").isNumber())
			.andExpect(jsonPath("$.data.notifications[0].title").isString())
			.andExpect(jsonPath("$.data.notifications[0].link").isString())
			.andExpect(jsonPath("$.data.notifications[0].group").isString())
			.andExpect(jsonPath("$.data.notifications[0].actors[0].memberId").isNumber())
			.andExpect(jsonPath("$.data.notifications[0].actors[0].characterCode").isString())
			.andExpect(jsonPath("$.data.notifications[0].actorCount").isNumber())
			.andExpect(jsonPath("$.data.hasNext").isBoolean());
	}
}
