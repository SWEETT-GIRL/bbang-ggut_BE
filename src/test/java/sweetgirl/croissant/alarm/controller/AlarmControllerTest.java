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

import sweetgirl.croissant.alarm.dto.response.ReportNotificationResponse;
import sweetgirl.croissant.alarm.service.AlarmCommandService;
import sweetgirl.croissant.alarm.service.AlarmQueryService;
import sweetgirl.croissant.alarm.service.FcmService;
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
}
