package sweetgirl.croissant.letter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

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

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.AdminLetterSendRequest;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadListResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadResponse;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.exception.LetterException;
import sweetgirl.croissant.letter.service.AdminLetterService;

@ExtendWith(MockitoExtension.class)
class AdminLetterControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminLetterService adminLetterService;

	@InjectMocks
	private AdminLetterController adminLetterController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(adminLetterController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("대화 목록은 닉네임·미리보기·미읽음 수를 담는다")
	void getThreads_returnsThreadRows() throws Exception {
		AdminLetterThreadResponse thread = new AdminLetterThreadResponse(
			11L, 7L, "포실한 까눌레", false, "네 좋아요!", LocalDateTime.of(2026, 9, 3, 10, 0), 2L,
			LetterCategory.BUG, false);
		given(adminLetterService.getThreads(0, 20))
			.willReturn(new AdminLetterThreadListResponse(List.of(thread), false));

		mockMvc.perform(get("/api/admin/letters/threads"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.threads[0].threadId").value(11))
			.andExpect(jsonPath("$.data.threads[0].nickname").value("포실한 까눌레"))
			.andExpect(jsonPath("$.data.threads[0].lastMessagePreview").value("네 좋아요!"))
			.andExpect(jsonPath("$.data.threads[0].unreadCount").value(2))
			.andExpect(jsonPath("$.data.threads[0].category").value("BUG"));
	}

	@Test
	@DisplayName("보내면 보낸 수와 건너뛴 회원을 함께 돌려준다")
	void send_returnsSentAndSkipped() throws Exception {
		given(adminLetterService.send(any(AdminLetterSendRequest.class)))
			.willReturn(new AdminLetterSendResponse(2, List.of(404L)));

		mockMvc.perform(post("/api/admin/letters/send")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memberIds\":[1,2,404],\"body\":\"인터뷰 가능하실까요?\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sentCount").value(2))
			.andExpect(jsonPath("$.data.skippedMemberIds[0]").value(404));
	}

	@Test
	@DisplayName("없는 대화방에 답장하면 404")
	void reply_returns404WhenThreadMissing() throws Exception {
		given(adminLetterService.reply(eq(99L), any()))
			.willThrow(new LetterException(LetterErrorCode.LETTER_THREAD_NOT_FOUND));

		mockMvc.perform(post("/api/admin/letters/threads/99/messages")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"안녕하세요\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LETTER404"));
	}

	@Test
	@DisplayName("읽음 처리는 서비스로 넘어간다")
	void markAsRead_callsService() throws Exception {
		mockMvc.perform(patch("/api/admin/letters/threads/11/read"))
			.andExpect(status().isOk());

		verify(adminLetterService).markAsRead(11L);
	}
}
