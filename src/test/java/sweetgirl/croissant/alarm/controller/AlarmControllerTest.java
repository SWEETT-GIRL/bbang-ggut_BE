package sweetgirl.croissant.alarm.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.alarm.dto.response.CourseSaveNotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.CourseSaveNotificationResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationResponse;
import sweetgirl.croissant.alarm.dto.response.ReportNotificationResponse;
import sweetgirl.croissant.alarm.dto.response.UnreadCountResponse;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.service.AlarmCommandService;
import sweetgirl.croissant.alarm.service.AlarmQueryService;
import sweetgirl.croissant.alarm.service.FcmService;
import sweetgirl.croissant.alarm.service.NotificationService;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AlarmControllerTest {

	private MockMvc mockMvc;

	@Mock
	private FcmService fcmService;

	@Mock
	private AlarmQueryService alarmQueryService;

	@Mock
	private AlarmCommandService alarmCommandService;

	@Mock
	private ReportNotificationService reportNotificationService;

	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private AlarmController alarmController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(alarmController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getMyReportNotifications_keepsLegacyResponseShape() throws Exception {
		LocalDateTime sentAt = LocalDateTime.of(2026, 6, 12, 11, 30);
		ReportNotificationResponse notification = new ReportNotificationResponse(
			1L,
			12L,
			100L,
			null,
			77L,
			"ABUSE_REPORT",
			"MODERATION_ACTION",
			null,
			null,
			"운영 정책 위반으로 콘텐츠가 제한되었습니다.",
			"결정에 동의하지 않는 경우 30일 이내에 이의제기를 요청할 수 있습니다.",
			false,
			null,
			sentAt);
		given(reportNotificationService.getMyReportNotifications(null))
			.willReturn(List.of(notification));

		mockMvc.perform(get("/alarm/report-notifications"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(1))
			.andExpect(jsonPath("$.data[0].reportId").value(12))
			.andExpect(jsonPath("$.data[0].contributionReportId").value(100))
			.andExpect(jsonPath("$.data[0].abuseReportId").doesNotExist())
			.andExpect(jsonPath("$.data[0].moderationActionId").value(77))
			.andExpect(jsonPath("$.data[0].reportKind").value("ABUSE_REPORT"))
			.andExpect(jsonPath("$.data[0].action").value("MODERATION_ACTION"))
			.andExpect(jsonPath("$.data[0].title").value("운영 정책 위반으로 콘텐츠가 제한되었습니다."))
			.andExpect(jsonPath("$.data[0].read").value(false))
			.andExpect(jsonPath("$.data[0].sentAt").value("2026-06-12T11:30:00"));
	}

	@Test
	void readReportNotification_keepsLegacyReadEndpoint() throws Exception {
		mockMvc.perform(patch("/alarm/report-notifications/1/read"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(reportNotificationService).markAsRead(null, 1L);
	}

	// ─────────────── 알림 (새 경로) ───────────────

	@Test
	void getMyNotifications_returnsAllTypes() throws Exception {
		NotificationResponse saved = new NotificationResponse(
			812L, NotificationType.COURSE_SAVED, false, LocalDateTime.of(2026, 8, 19, 10, 31),
			2437L, "포실한 까눌레", NotificationTargetType.COURSE, 352L, "8월 20일 부산 빵투어",
			null, null, null);
		NotificationResponse notice = new NotificationResponse(
			900L, NotificationType.NOTICE, false, LocalDateTime.of(2026, 9, 3, 9, 0),
			null, null, null, null, null,
			"일차 나누기가 생겼어요", "1박 2일 빵투어를 나눠 담아보세요", "/course/12");
		// standalone MockMvc엔 @AuthenticationPrincipal 리졸버가 없어 memberId는 null로 들어온다
		// (이 파일의 다른 테스트들과 같은 전제). 여기서 보는 건 경로·기본 페이징·응답 모양이다.
		given(notificationService.getMine(null, 0, 20))
			.willReturn(new NotificationListResponse(List.of(saved, notice), true));

		mockMvc.perform(get("/alarm/notifications"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.notifications[0].type").value("COURSE_SAVED"))
			.andExpect(jsonPath("$.data.notifications[0].targetTitle").value("8월 20일 부산 빵투어"))
			// 공지는 actor·target이 비고 서버 문구가 실려 온다
			.andExpect(jsonPath("$.data.notifications[1].type").value("NOTICE"))
			.andExpect(jsonPath("$.data.notifications[1].actorNickname").doesNotExist())
			.andExpect(jsonPath("$.data.notifications[1].title").value("일차 나누기가 생겼어요"))
			.andExpect(jsonPath("$.data.notifications[1].link").value("/course/12"));
	}

	@Test
	void getNotificationUnreadCount_returnsCount() throws Exception {
		given(notificationService.getUnreadCount(null)).willReturn(new UnreadCountResponse(3L));

		mockMvc.perform(get("/alarm/notifications/unread-count"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.unreadCount").value(3));
	}

	@Test
	void readNotifications_withoutBody_marksAll() throws Exception {
		mockMvc.perform(patch("/alarm/notifications/read"))
			.andExpect(status().isOk());

		verify(notificationService).markAsRead(null, null);
	}

	// ─────────────── 구버전 경로 (저장 알림만) ───────────────

	@Test
	void legacyCourseNotifications_keepOldShapeAndFilterToCourseSaved() throws Exception {
		NotificationResponse saved = new NotificationResponse(
			812L, NotificationType.COURSE_SAVED, false, LocalDateTime.of(2026, 8, 19, 10, 31),
			2437L, "포실한 까눌레", NotificationTargetType.COURSE, 352L, "8월 20일 부산 빵투어",
			null, null, null);
		given(notificationService.getMineByType(null, NotificationType.COURSE_SAVED, 0, 20))
			.willReturn(new NotificationListResponse(List.of(saved), true));

		mockMvc.perform(get("/alarm/course-notifications"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.notifications[0].notificationId").value(812))
			.andExpect(jsonPath("$.data.notifications[0].type").value("COURSE_SAVED"))
			.andExpect(jsonPath("$.data.notifications[0].actorNickname").value("포실한 까눌레"))
			// 옛 앱이 읽는 이름 그대로여야 한다 — targetId/targetTitle이 아니라 courseId/courseTitle
			.andExpect(jsonPath("$.data.notifications[0].courseId").value(352))
			.andExpect(jsonPath("$.data.notifications[0].courseTitle").value("8월 20일 부산 빵투어"));
	}

	@Test
	void legacyUnreadCount_countsOnlyCourseSaved() throws Exception {
		given(notificationService.getUnreadCountByType(null, NotificationType.COURSE_SAVED))
			.willReturn(new UnreadCountResponse(3L));

		mockMvc.perform(get("/alarm/course-notifications/unread-count"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.unreadCount").value(3));
	}

	@Test
	void legacyRead_marksOnlyCourseSaved() throws Exception {
		mockMvc.perform(patch("/alarm/course-notifications/read"))
			.andExpect(status().isOk());

		verify(notificationService).markAsReadByType(null, NotificationType.COURSE_SAVED, null);
	}
}
