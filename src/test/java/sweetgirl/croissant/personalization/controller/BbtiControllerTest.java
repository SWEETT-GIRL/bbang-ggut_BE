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
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
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
			{"answers": [
				{"axis": "taste", "choice": "B"}, {"axis": "texture", "choice": "B"},
				{"axis": "filling", "choice": "B"}, {"axis": "vibe", "choice": "B"}
			]}
			""";

	@Test
	@DisplayName("검사 응답을 보내면 FE 계약 그대로 typeCode·typeLabel·axis[4]·confidence 가 온다")
	void submit_returnsContractShape() throws Exception {
		BigDecimal one = new BigDecimal("1.000");
		given(tasteQuizCommandService.submit(eq(null), any()))
			.willReturn(new BbtiResultResponse("PCLN", "뉴웨이브 미니멀", List.of(one, one, one, one), 0.7));

		mockMvc.perform(post("/users/me/bbti").contentType(MediaType.APPLICATION_JSON).content(SHORT_QUIZ))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.typeCode").value("PCLN"))
			.andExpect(jsonPath("$.data.typeLabel").value("뉴웨이브 미니멀"))
			.andExpect(jsonPath("$.data.axis.length()").value(4))
			.andExpect(jsonPath("$.data.axis[0]").value(1.0))
			.andExpect(jsonPath("$.data.confidence").value(0.7));
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
}
