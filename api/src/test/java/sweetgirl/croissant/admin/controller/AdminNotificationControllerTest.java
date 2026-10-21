package sweetgirl.croissant.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.alarm.dto.request.NoticeSendRequest;
import sweetgirl.croissant.alarm.dto.response.NoticeSendResponse;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.service.NotificationService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminNotificationControllerTest {

	private MockMvc mockMvc;

	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private AdminNotificationController adminNotificationController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(adminNotificationController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("채널을 생략하면 서비스가 기본값을 정하도록 그대로 넘긴다")
	void sendNotice_withoutChannel_passesNullThrough() throws Exception {
		given(notificationService.sendNotice(any()))
			.willReturn(new NoticeSendResponse(1234, NotificationChannel.PUSH));

		mockMvc.perform(post("/api/admin/notifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"3.2.0 업데이트","body":"빵투어 공유가 생겼어요","link":"/course/12"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.recipientCount").value(1234))
			.andExpect(jsonPath("$.data.channel").value("PUSH"));

		// 컨트롤러가 기본값을 손대지 않는다 -- 그 판단은 서비스 한 곳에만 있어야 한다.
		ArgumentCaptor<NoticeSendRequest> captor = ArgumentCaptor.forClass(NoticeSendRequest.class);
		verify(notificationService).sendNotice(captor.capture());
		assertThatChannelIs(captor, null);
	}

	@Test
	@DisplayName("IN_APP 공지는 그대로 서비스에 전달되고 응답에도 그대로 실린다")
	void sendNotice_inAppOnly() throws Exception {
		given(notificationService.sendNotice(any()))
			.willReturn(new NoticeSendResponse(1234, NotificationChannel.IN_APP));

		mockMvc.perform(post("/api/admin/notifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"3.2.0 업데이트","body":"본문","channel":"IN_APP"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.channel").value("IN_APP"));

		ArgumentCaptor<NoticeSendRequest> captor = ArgumentCaptor.forClass(NoticeSendRequest.class);
		verify(notificationService).sendNotice(captor.capture());
		assertThatChannelIs(captor, NotificationChannel.IN_APP);
	}

	@Test
	@DisplayName("없는 채널 이름은 400 — 오타가 조용히 기본값으로 흘러가면 안 된다")
	void sendNotice_unknownChannel_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/admin/notifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"제목","body":"본문","channel":"SMS"}"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("제목이 비면 400")
	void sendNotice_blankTitle_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/admin/notifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"","body":"본문"}"""))
			.andExpect(status().isBadRequest());
	}

	private void assertThatChannelIs(ArgumentCaptor<NoticeSendRequest> captor,
			NotificationChannel expected) {
		assertThat(captor.getValue().getChannel()).isEqualTo(expected);
	}
}
