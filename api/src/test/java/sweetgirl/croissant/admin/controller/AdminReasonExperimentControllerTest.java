package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentInput;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentResponse;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentRun;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentStore;
import sweetgirl.croissant.recommendation.dto.response.ReasonSentence;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.enums.ReasonSource;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService;

@ExtendWith(MockitoExtension.class)
class AdminReasonExperimentControllerTest {

	MockMvc mockMvc;

	@Mock
	ReasonExperimentService reasonExperimentService;

	@Mock
	LlmClientRegistry llmClientRegistry;

	@InjectMocks
	AdminReasonExperimentController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private static ReasonExperimentResponse measured() {
		ReasonExperimentInput input = new ReasonExperimentInput(7L, "성수동", "PCLN", true, false,
			List.of(new ReasonExperimentStore(1L, "밀도", "성수동2가", 0.71, "이 동네에서 자주 담기는 빵집이에요")));
		ReasonExperimentRun run = new ReasonExperimentRun("GEMINI/gemini-3.5-flash-lite/A",
			LlmProvider.GEMINI, "gemini-3.5-flash-lite", ReasonPromptVariant.A, 3, 0, 640L, 812L, 700, 48, 0, 1_200,
			List.of(new ReasonSentence(1L, "밀도", "바삭한 소금빵이 강한 집이에요", ReasonSource.LLM)), null);
		return ReasonExperimentResponse.measured(input, List.of(run), List.of());
	}

	@Test
	@DisplayName("조합별 지연·토큰·문장을 돌려준다")
	void runExperiment_returnsRuns() throws Exception {
		given(reasonExperimentService.run(any())).willReturn(measured());

		mockMvc.perform(post("/admin/recommendations/reason-experiment")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"memberId":42,"areaId":7,"storeCount":4,"models":["GEMINI:gemini-3.5-flash-lite"],
					"variants":["A"],"blind":false}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.input.areaName").value("성수동"))
			.andExpect(jsonPath("$.data.input.stores[0].ruleReason").value("이 동네에서 자주 담기는 빵집이에요"))
			.andExpect(jsonPath("$.data.runs[0].latencyMedianMs").value(640))
			.andExpect(jsonPath("$.data.runs[0].latencyMaxMs").value(812))
			.andExpect(jsonPath("$.data.runs[0].reasons[0].reason").value("바삭한 소금빵이 강한 집이에요"));
	}

	@Test
	@DisplayName("회원 ID 가 없으면 400 — 서비스는 부르지 않는다")
	void runExperiment_rejectsMissingMemberId() throws Exception {
		mockMvc.perform(post("/admin/recommendations/reason-experiment")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"areaId":7}
					"""))
			.andExpect(status().isBadRequest());

		verify(reasonExperimentService, never()).run(any());
	}

	@Test
	@DisplayName("상권이 없으면 404")
	void runExperiment_unknownArea() throws Exception {
		willThrow(new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND))
			.given(reasonExperimentService).run(any());

		mockMvc.perform(post("/admin/recommendations/reason-experiment")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"memberId":42,"areaId":999}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECOMMENDATION404_2"));
	}

	@Test
	@DisplayName("조합 상한을 넘으면 400")
	void runExperiment_tooManyCombinations() throws Exception {
		willThrow(new RecommendationException(RecommendationErrorCode.EXPERIMENT_TOO_MANY_COMBINATIONS))
			.given(reasonExperimentService).run(any());

		mockMvc.perform(post("/admin/recommendations/reason-experiment")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"memberId":42,"areaId":7}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("RECOMMENDATION400_12"));
	}

	@Test
	@DisplayName("키가 설정된 벤더만 알려 준다 — 키 값은 나가지 않는다")
	void configuredProviders_returnsProviders() throws Exception {
		given(llmClientRegistry.configuredProviders()).willReturn(List.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC));

		mockMvc.perform(get("/admin/recommendations/reason-experiment/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0]").value("GEMINI"))
			.andExpect(jsonPath("$.data[1]").value("ANTHROPIC"));
	}
}
