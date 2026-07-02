package sweetgirl.croissant.abuse.controller;

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

import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.abuse.dto.response.AbuseReportCreateResponse;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.service.AbuseReportCommandService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AbuseReportControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AbuseReportCommandService abuseReportCommandService;

	@InjectMocks
	private AbuseReportController abuseReportController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(abuseReportController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void createReport_returns201() throws Exception {
		given(abuseReportCommandService.createReport(isNull(), any()))
			.willReturn(new AbuseReportCreateResponse(
				11L,
				AbuseReportStatus.PENDING,
				LocalDateTime.of(2026, 6, 4, 12, 0)));

		mockMvc.perform(post("/abuse-reports")
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
		mockMvc.perform(post("/abuse-reports")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"REVIEW\",\"reasonType\":\"ABUSE\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void createReport_returns409_whenAlreadyReported() throws Exception {
		given(abuseReportCommandService.createReport(isNull(), any()))
			.willThrow(new AbuseReportException(AbuseReportErrorCode.REPORT_ALREADY_EXISTS));

		mockMvc.perform(post("/abuse-reports")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"REVIEW\",\"targetId\":1,\"reasonType\":\"ABUSE\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REPORT_ALREADY_EXISTS"));
	}
}
