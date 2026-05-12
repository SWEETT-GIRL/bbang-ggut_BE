package sweetgirl.croissant.policy.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.policy.dto.response.TermsResponse;
import sweetgirl.croissant.policy.enums.TermsType;
import sweetgirl.croissant.policy.service.TermsService;

@ExtendWith(MockitoExtension.class)
class PolicyControllerTest {

	private MockMvc mockMvc;

	@Mock
	private TermsService termsService;

	@InjectMocks
	private TermsController termsController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(termsController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getAllTerms_returns200WithThreeTerms() throws Exception {
		given(termsService.getTerms()).willReturn(List.of(
			new TermsResponse(TermsType.SERVICE, "서비스 이용약관", true, "제1조..."),
			new TermsResponse(TermsType.PRIVACY, "개인정보처리방침", true, "제1조..."),
			new TermsResponse(TermsType.MARKETING, "마케팅 정보 수신 동의", false, "마케팅...")));

		mockMvc.perform(get("/terms"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.length()").value(3))
			.andExpect(jsonPath("$.data[0].type").value("SERVICE"))
			.andExpect(jsonPath("$.data[1].type").value("PRIVACY"))
			.andExpect(jsonPath("$.data[2].isRequired").value(false));
	}
}
