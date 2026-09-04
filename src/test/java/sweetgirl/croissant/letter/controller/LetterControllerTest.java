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
import sweetgirl.croissant.letter.dto.request.LetterSendRequest;
import sweetgirl.croissant.letter.dto.response.LetterListResponse;
import sweetgirl.croissant.letter.dto.response.LetterMessageResponse;
import sweetgirl.croissant.letter.dto.response.LetterUnreadCountResponse;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.exception.LetterException;
import sweetgirl.croissant.letter.service.LetterService;

@ExtendWith(MockitoExtension.class)
class LetterControllerTest {

	private MockMvc mockMvc;

	@Mock
	private LetterService letterService;

	@InjectMocks
	private LetterController letterController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(letterController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("편지 목록은 sender와 본문을 그대로 내려준다")
	void getMyLetters_returnsMessages() throws Exception {
		LetterMessageResponse message = new LetterMessageResponse(
			5L, LetterSender.ADMIN, "인터뷰 가능하실까요?", LetterCategory.OUTREACH, false,
			LocalDateTime.of(2026, 9, 3, 10, 0));
		given(letterService.getMyLetters(any(), eq(0), eq(20)))
			.willReturn(new LetterListResponse(List.of(message), true, false));

		mockMvc.perform(get("/letters").param("page", "0").param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.messages[0].messageId").value(5))
			.andExpect(jsonPath("$.data.messages[0].sender").value("ADMIN"))
			.andExpect(jsonPath("$.data.messages[0].body").value("인터뷰 가능하실까요?"))
			.andExpect(jsonPath("$.data.messages[0].read").value(false))
			.andExpect(jsonPath("$.data.hasNext").value(true));
	}

	@Test
	@DisplayName("page/size를 안 주면 0, 20이 기본이다")
	void getMyLetters_usesDefaultPaging() throws Exception {
		given(letterService.getMyLetters(any(), eq(0), eq(20))).willReturn(LetterListResponse.empty());

		mockMvc.perform(get("/letters")).andExpect(status().isOk());

		verify(letterService).getMyLetters(any(), eq(0), eq(20));
	}

	@Test
	@DisplayName("편지를 보내면 방금 보낸 편지가 돌아온다")
	void send_returnsSavedMessage() throws Exception {
		given(letterService.send(any(), any(LetterSendRequest.class)))
			.willReturn(new LetterMessageResponse(
				9L, LetterSender.MEMBER, "네 좋아요!", null, false, LocalDateTime.of(2026, 9, 3, 11, 0)));

		mockMvc.perform(post("/letters")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"네 좋아요!\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messageId").value(9))
			.andExpect(jsonPath("$.data.sender").value("MEMBER"));
	}

	@Test
	@DisplayName("쿨다운에 걸리면 429")
	void send_returns429WhenTooFrequent() throws Exception {
		given(letterService.send(any(), any(LetterSendRequest.class)))
			.willThrow(new LetterException(LetterErrorCode.LETTER_TOO_FREQUENT));

		mockMvc.perform(post("/letters")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"도배\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("LETTER429"));
	}

	@Test
	@DisplayName("미읽음 개수는 숫자 하나다")
	void getUnreadCount_returnsCount() throws Exception {
		given(letterService.getUnreadCount(any())).willReturn(new LetterUnreadCountResponse(3L));

		mockMvc.perform(get("/letters/unread-count"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.unreadCount").value(3));
	}

	@Test
	@DisplayName("읽음 처리는 body 없이 부른다")
	void markAsRead_callsService() throws Exception {
		mockMvc.perform(patch("/letters/read"))
			.andExpect(status().isOk());

		verify(letterService).markAsRead(any());
	}
}
