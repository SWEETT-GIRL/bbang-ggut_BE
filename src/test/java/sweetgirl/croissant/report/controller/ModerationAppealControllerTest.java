package sweetgirl.croissant.report.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import sweetgirl.croissant.report.code.UserReportErrorCode;
import sweetgirl.croissant.report.dto.response.ModerationAppealCreateResponse;
import sweetgirl.croissant.report.enums.ModerationAppealStatus;
import sweetgirl.croissant.report.exception.UserReportException;
import sweetgirl.croissant.report.service.ModerationAppealService;

@ExtendWith(MockitoExtension.class)
class ModerationAppealControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ModerationAppealService moderationAppealService;

	@InjectMocks
	private ModerationAppealController moderationAppealController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(moderationAppealController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void createAppeal_returns201() throws Exception {
		given(moderationAppealService.createAppeal(isNull(), any()))
			.willReturn(new ModerationAppealCreateResponse(
				100L,
				55L,
				ModerationAppealStatus.PENDING,
				LocalDateTime.of(2026, 6, 5, 10, 0)));

		mockMvc.perform(post("/moderation-appeals")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"actionId\":55,\"content\":\"해당 리뷰는 욕설이 아닙니다.\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.appealId").value(100))
			.andExpect(jsonPath("$.data.actionId").value(55))
			.andExpect(jsonPath("$.data.status").value("PENDING"));
	}

	@Test
	void createAppeal_returns400_whenContentMissing() throws Exception {
		mockMvc.perform(post("/moderation-appeals")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"actionId\":55}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void createAppeal_returns409_whenAlreadyAppealed() throws Exception {
		given(moderationAppealService.createAppeal(isNull(), any()))
			.willThrow(new UserReportException(UserReportErrorCode.APPEAL_ALREADY_EXISTS));

		mockMvc.perform(post("/moderation-appeals")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"actionId\":55,\"content\":\"이의제기합니다.\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPEAL_ALREADY_EXISTS"));
	}
}
