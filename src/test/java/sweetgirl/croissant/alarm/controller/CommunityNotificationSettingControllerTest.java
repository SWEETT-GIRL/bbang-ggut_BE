package sweetgirl.croissant.alarm.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.alarm.dto.response.CommunityNotificationSettingResponse;
import sweetgirl.croissant.alarm.service.AlarmCommandService;
import sweetgirl.croissant.alarm.service.AlarmQueryService;
import sweetgirl.croissant.alarm.service.NotificationService;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlarmController — 커뮤니티 알림 스위치")
class CommunityNotificationSettingControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AlarmQueryService alarmQueryService;
	@Mock
	private AlarmCommandService alarmCommandService;
	@Mock
	private NotificationService notificationService;
	@Mock
	private ReportNotificationService reportNotificationService;

	@InjectMocks
	private AlarmController alarmController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(alarmController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET · PATCH /alarm/settings/community — 다섯 스위치, PATCH 는 바뀐 키만")
	void settings() throws Exception {
		given(alarmQueryService.getCommunitySettings(any()))
			.willReturn(new CommunityNotificationSettingResponse(true, true, true, true, true));
		mockMvc.perform(get("/alarm/settings/community"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.follow").value(true))
			.andExpect(jsonPath("$.data.followingRecord").value(true));

		given(alarmCommandService.updateCommunitySettings(any(), any()))
			.willReturn(new CommunityNotificationSettingResponse(true, true, false, true, true));
		mockMvc.perform(patch("/alarm/settings/community").contentType(MediaType.APPLICATION_JSON)
			.content("{\"like\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.like").value(false));
	}
}
