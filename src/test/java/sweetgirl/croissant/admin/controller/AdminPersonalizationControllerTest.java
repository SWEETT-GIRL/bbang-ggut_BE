package sweetgirl.croissant.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.dto.response.InteractionIngestResult;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

@ExtendWith(MockitoExtension.class)
class AdminPersonalizationControllerTest {

	MockMvc mockMvc;
	@Mock
	UserInteractionIngestCommandService ingestCommandService;
	@InjectMocks
	AdminPersonalizationController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("날짜 없이 부르면 어제분, 결과는 종류별 행 수까지")
	void ingest_withoutRange_ingestsYesterday() throws Exception {
		given(ingestCommandService.ingestYesterday()).willReturn(new InteractionIngestResult(
			LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18), 46, Map.of(InteractionType.SAVE, 6,
				InteractionType.STORE_VIEW, 40)));

		mockMvc.perform(post("/admin/personalization/interactions/ingest"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.from").value("2026-09-17"))
			.andExpect(jsonPath("$.data.inserted").value(46))
			.andExpect(jsonPath("$.data.byType.STORE_VIEW").value(40));
	}

	@Test
	@DisplayName("from · to 를 주면 그 범위로")
	void ingest_withRange_passesDates() throws Exception {
		given(ingestCommandService.ingest(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)))
			.willReturn(new InteractionIngestResult(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), 900,
				Map.of()));

		mockMvc.perform(post("/admin/personalization/interactions/ingest")
			.param("from", "2026-08-01").param("to", "2026-09-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.inserted").value(900));
		then(ingestCommandService).should().ingest(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
	}

	@Test
	@DisplayName("from 만 주면 그 하루, to 만 주면 400 — 한쪽만 온 요청이 500 으로 새지 않는다")
	void ingest_withOneSidedRange() throws Exception {
		given(ingestCommandService.ingest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)))
			.willReturn(new InteractionIngestResult(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), 3, Map.of()));

		mockMvc.perform(post("/admin/personalization/interactions/ingest").param("from", "2026-09-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.to").value("2026-09-02"));
		mockMvc.perform(post("/admin/personalization/interactions/ingest").param("to", "2026-09-02"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERSONALIZATION400_2"));
	}

	@Test
	@DisplayName("다른 적재가 실행 중이면 409")
	void ingest_whenRunning_isConflict() throws Exception {
		given(ingestCommandService.ingestYesterday())
			.willThrow(new PersonalizationException(PersonalizationErrorCode.INTERACTION_INGEST_ALREADY_RUNNING));

		mockMvc.perform(post("/admin/personalization/interactions/ingest"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERSONALIZATION409_1"));
	}

	@Test
	@DisplayName("담기 백필은 until 필수, 새로 들어간 행 수를 돌려준다")
	void backfillCourseAdds_returnsInserted() throws Exception {
		given(ingestCommandService.backfillCourseAddsByOwner(LocalDate.of(2026, 9, 19))).willReturn(1_910);

		mockMvc.perform(post("/admin/personalization/interactions/backfill-course-adds").param("until", "2026-09-19"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").value(1_910));
		mockMvc.perform(post("/admin/personalization/interactions/backfill-course-adds"))
			.andExpect(status().isBadRequest());
	}
}
