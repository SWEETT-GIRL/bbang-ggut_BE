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
import sweetgirl.croissant.report.dto.response.UserReportCreateResponse;
import sweetgirl.croissant.report.enums.UserReportStatus;
import sweetgirl.croissant.report.exception.UserReportException;
import sweetgirl.croissant.report.service.UserReportService;

@ExtendWith(MockitoExtension.class)
class UserReportControllerTest {

	private MockMvc mockMvc;

	@Mock
	private UserReportService userReportService;

	@InjectMocks
	private UserReportController userReportController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(userReportController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void createReport_returns201() throws Exception {
		given(userReportService.createReport(isNull(), any()))
			.willReturn(new UserReportCreateResponse(
				11L,
				UserReportStatus.PENDING,
				LocalDateTime.of(2026, 6, 4, 12, 0)));

		mockMvc.perform(post("/reports")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"REVIEW\",\"targetId\":1,"
				+ "\"reasonType\":\"ABUSE\",\"reasonDetail\":\"욕설이 포함되어 있어요\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reportId").value(11))
			.andExpect(jsonPath("$.data.status").value("PENDING"));
	}

	@Test
	void createReport_returns400_whenTargetIdMissing() throws Exception {
		mockMvc.perform(post("/reports")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"REVIEW\",\"reasonType\":\"ABUSE\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void createReport_returns409_whenAlreadyReported() throws Exception {
		given(userReportService.createReport(isNull(), any()))
			.willThrow(new UserReportException(UserReportErrorCode.REPORT_ALREADY_EXISTS));

		mockMvc.perform(post("/reports")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"REVIEW\",\"targetId\":1,\"reasonType\":\"ABUSE\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REPORT_ALREADY_EXISTS"));
	}
}
