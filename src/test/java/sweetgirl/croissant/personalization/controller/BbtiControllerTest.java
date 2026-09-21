package sweetgirl.croissant.personalization.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
import sweetgirl.croissant.personalization.dto.response.TasteLabels;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.service.TasteQuizCommandService;

@ExtendWith(MockitoExtension.class)
class BbtiControllerTest {

	private MockMvc mockMvc;

	@Mock
	private TasteQuizCommandService tasteQuizCommandService;

	@InjectMocks
	private BbtiController bbtiController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(bbtiController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private static final String SHORT_QUIZ = """
			{"quizVersion": 2, "answers": [
				{"axis": "taste", "choice": "SALTY"}, {"axis": "texture", "choice": "CHEWY"},
				{"axis": "filling", "choice": "SIMPLE"}, {"axis": "vibe", "choice": "TRENDY"}
			]}
			""";

	/** 맛 문항에 식감 갈래를 넣은 요청 — 자리 문자(A/B)였다면 조용히 통과했을 자리다 */
	private static final String MISMATCHED_QUIZ = """
			{"quizVersion": 2, "answers": [
				{"axis": "taste", "choice": "CHEWY"}, {"axis": "texture", "choice": "CRISP"},
				{"axis": "filling", "choice": "SIMPLE"}, {"axis": "vibe", "choice": "TRENDY"}
			]}
			""";

	@Test
	@DisplayName("검사 응답을 보내면 typeCode·axis[4]·labels·confidence 가 온다 — 유형 이름(typeLabel)은 없다(#760)")
	void submit_returnsContractShape() throws Exception {
		BigDecimal one = new BigDecimal("1.000");
		TasteLabels labels = TasteLabels.of(List.of(QuizChoice.SALTY, QuizChoice.CHEWY, QuizChoice.SIMPLE,
			QuizChoice.TRENDY));
		given(tasteQuizCommandService.submit(eq(null), any()))
			.willReturn(new BbtiResultResponse("TYLN", List.of(one, one, one, one), labels, 0.7));

		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON).content(SHORT_QUIZ))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.typeCode").value("TYLN"))
			.andExpect(jsonPath("$.data.typeLabel").doesNotExist())
			.andExpect(jsonPath("$.data.axis.length()").value(4))
			.andExpect(jsonPath("$.data.axis[0]").value(1.0))
			.andExpect(jsonPath("$.data.labels.salty").value(1.0))
			.andExpect(jsonPath("$.data.labels.chewy").value(1.0))
			.andExpect(jsonPath("$.data.labels.sweet").value(0.0))
			.andExpect(jsonPath("$.data.labels.aroma").value(0.0))
			.andExpect(jsonPath("$.data.labels.filling").value(1.0))
			.andExpect(jsonPath("$.data.labels.vibe").value(1.0))
			.andExpect(jsonPath("$.data.confidence").value(0.7));
	}

	@Test
	@DisplayName("맛 문항에 식감 갈래가 오면 400 이고 서비스는 불리지 않는다")
	void submit_choiceFromAnotherAxis_isBadRequest() throws Exception {
		given(tasteQuizCommandService.submit(eq(null), any()))
			.willThrow(new PersonalizationException(PersonalizationErrorCode.QUIZ_CHOICE_AXIS_MISMATCH));

		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON).content(MISMATCHED_QUIZ))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("PERSONALIZATION400_5"));
	}

	@Test
	@DisplayName("규칙에 없는 선택지 이름은 본문 파싱에서 400 — 옛 앱의 A/B 가 여기서 걸린다")
	void submit_unknownChoiceName_isBadRequest() throws Exception {
		String legacy = """
				{"answers": [
					{"axis": "taste", "choice": "A"}, {"axis": "texture", "choice": "B"},
					{"axis": "filling", "choice": "A"}, {"axis": "vibe", "choice": "B"}
				]}
				""";

		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON).content(legacy))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("답이 비어 있으면 400 이고 서비스는 불리지 않는다")
	void submit_emptyAnswers_isBadRequest() throws Exception {
		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON)
			.content("{\"answers\": []}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	@DisplayName("모양이 틀린 답은 서비스의 입력 오류 코드로 400 이다")
	void submit_wrongShape_returnsErrorCode() throws Exception {
		given(tasteQuizCommandService.submit(eq(null), any()))
			.willThrow(new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID));

		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON).content(SHORT_QUIZ))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERSONALIZATION400_1"));
	}

	@Test
	@DisplayName("모르는 축 이름은 본문 파싱 단계에서 공통 400 이다 — 도메인 코드가 아니다")
	void submit_unknownAxis_isBadRequest() throws Exception {
		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON)
			.content("{\"answers\": [{\"axis\": \"vibes\", \"choice\": \"A\"}]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false));
	}
}
