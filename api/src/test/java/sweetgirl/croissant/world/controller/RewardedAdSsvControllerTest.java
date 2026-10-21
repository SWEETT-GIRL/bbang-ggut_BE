package sweetgirl.croissant.world.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.service.RewardedAdSsvService;

/**
 * 이 콜백은 <b>무슨 일이 있어도 200</b> 이어야 한다. 4xx · 5xx 를 주면 구글이 같은 콜백을 끝없이
 * 재시도하고, 그 재시도가 쌓이는 동안 진짜 콜백까지 밀린다.
 */
@ExtendWith(MockitoExtension.class)
class RewardedAdSsvControllerTest {

	private MockMvc mockMvc;

	@Mock
	private RewardedAdSsvService rewardedAdSsvService;

	@InjectMocks
	private RewardedAdSsvController rewardedAdSsvController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(rewardedAdSsvController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("적립했으면 200")
	void returnsOkWhenCredited() throws Exception {
		given(rewardedAdSsvService.credit(any())).willReturn(true);

		mockMvc.perform(get("/ads/rewarded/ssv").param("transaction_id", "TX1"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("서명이 틀려 안 적립해도 200")
	void returnsOkWhenRejected() throws Exception {
		given(rewardedAdSsvService.credit(any())).willReturn(false);

		mockMvc.perform(get("/ads/rewarded/ssv").param("signature", "bad"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("DB 가 터져도 200 — 500 을 주면 구글이 끝없이 재시도한다")
	void returnsOkEvenWhenStorageFails() throws Exception {
		willThrow(new DataIntegrityViolationException("boom"))
			.given(rewardedAdSsvService).credit(any());

		mockMvc.perform(get("/ads/rewarded/ssv").param("transaction_id", "TX1"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("세계관 예외가 나도 200")
	void returnsOkEvenOnWorldException() throws Exception {
		willThrow(new WorldException(WorldErrorCode.WORLD_CATALOG_NOT_INITIALIZED))
			.given(rewardedAdSsvService).credit(any());

		mockMvc.perform(get("/ads/rewarded/ssv"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("쿼리 문자열을 그대로 서비스에 넘긴다 — 다시 조립하면 서명이 깨진다")
	void passesRawQueryString() throws Exception {
		given(rewardedAdSsvService.credit(any())).willReturn(true);

		mockMvc.perform(get("/ads/rewarded/ssv?custom_data=7%3Afrog&signature=SIG&key_id=1"))
			.andExpect(status().isOk());

		ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
		then(rewardedAdSsvService).should().credit(query.capture());
		// 파싱해서 다시 조립한 값이 아니라 온 그대로여야 한다 — 순서가 바뀌면 서명이 안 맞는다
		assertThat(query.getValue()).startsWith("custom_data=").endsWith("key_id=1");
	}
}
