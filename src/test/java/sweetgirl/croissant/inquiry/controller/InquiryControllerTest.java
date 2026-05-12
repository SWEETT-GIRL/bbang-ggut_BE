package sweetgirl.croissant.inquiry.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.inquiry.dto.response.InquiryCreateResponse;
import sweetgirl.croissant.inquiry.service.InquiryService;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;

@ExtendWith(MockitoExtension.class)
class InquiryControllerTest {

	private MockMvc mockMvc;

	@Mock
	private InquiryService inquiryService;

	@InjectMocks
	private InquiryController inquiryController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(inquiryController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	// ── POST /inquiries ───────────────────────────────────────────────────────

	@Test
	void createInquiry_returns201_whenValidRequest() throws Exception {
		given(inquiryService.createInquiry(any(), any()))
			.willReturn(new InquiryCreateResponse(1L, LocalDateTime.now()));

		mockMvc.perform(post("/inquiries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"STORE\",\"title\":\"빵집 정보 오류\",\"content\":\"주소가 틀렸어요\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.inquiryId").value(1));
	}

	@Test
	void createInquiry_returns400_whenTitleIsBlank() throws Exception {
		mockMvc.perform(post("/inquiries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"BUG\",\"title\":\"\",\"content\":\"내용\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void createInquiry_returns400_whenCategoryIsNull() throws Exception {
		mockMvc.perform(post("/inquiries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"제목\",\"content\":\"내용\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void createInquiry_returns404_whenMemberNotFound() throws Exception {
		given(inquiryService.createInquiry(any(), any()))
			.willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		mockMvc.perform(post("/inquiries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"ETC\",\"title\":\"제목\",\"content\":\"내용\"}"))
			.andExpect(status().isNotFound());
	}
}
