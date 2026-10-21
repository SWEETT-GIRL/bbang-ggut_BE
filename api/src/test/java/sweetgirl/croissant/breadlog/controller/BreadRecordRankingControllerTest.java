package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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

import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.enums.RankingBoard;
import sweetgirl.croissant.breadlog.enums.RankingScope;
import sweetgirl.croissant.breadlog.service.BreadRecordRankingService;
import sweetgirl.croissant.breadlog.service.RankingRegionService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class BreadRecordRankingControllerTest {

	private static final String REGION_PATH = "/bread-records/ranking/region";

	private MockMvc mockMvc;

	@Mock
	BreadRecordRankingService rankingService;
	@Mock
	RankingRegionService regionService;

	@InjectMocks
	BreadRecordRankingController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("PUT /region — 좌표를 보내면 인증한 시·도·시·군·구를 돌려준다")
	void verifyRegion_returns200() throws Exception {
		given(regionService.verifyRegion(isNull(), eq(37.5744), eq(127.0396)))
			.willReturn(new RankingRegionResponse("서울", "동대문구", LocalDateTime.of(2026, 10, 1, 10, 0)));

		mockMvc.perform(put(REGION_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":37.5744,\"longitude\":127.0396}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sido").value("서울"))
			.andExpect(jsonPath("$.data.sigungu").value("동대문구"))
			.andExpect(jsonPath("$.data.verifiedAt").value("2026-10-01T10:00:00"));
	}

	@Test
	@DisplayName("PUT /region — 한국 범위 밖 좌표는 400, 서비스는 부르지 않는다")
	void verifyRegion_outsideKorea_returns400() throws Exception {
		mockMvc.perform(put(REGION_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":35.6762,\"longitude\":139.6503}"))
			.andExpect(status().isBadRequest());

		verify(regionService, never()).verifyRegion(any(), anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("PUT /region — 좌표가 빠지면 400")
	void verifyRegion_missingCoordinate_returns400() throws Exception {
		mockMvc.perform(put(REGION_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":37.5744}"))
			.andExpect(status().isBadRequest());

		verify(regionService, never()).verifyRegion(any(), anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("GET /region — 아직 인증 안 했으면 전부 null")
	void getRegion_returns200_withNulls() throws Exception {
		given(regionService.getRegion(isNull())).willReturn(RankingRegionResponse.empty());

		mockMvc.perform(get(REGION_PATH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sido").isEmpty())
			.andExpect(jsonPath("$.data.sigungu").isEmpty())
			.andExpect(jsonPath("$.data.verifiedAt").isEmpty());
	}

	@Test
	@DisplayName("GET /ranking?scope=LOCAL — 내 지역 판 응답에 시·군·구가 실린다")
	void getRanking_local_returns200() throws Exception {
		given(rankingService.getRanking(isNull(), eq(RankingBoard.WEEKLY), eq(RankingScope.LOCAL), eq(100)))
			.willReturn(new BreadRecordRankingResponse("서울", "동대문구", List.of(), null));

		mockMvc.perform(get("/bread-records/ranking")
			.param("board", "WEEKLY")
			.param("scope", "LOCAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sido").value("서울"))
			.andExpect(jsonPath("$.data.sigungu").value("동대문구"));
	}
}
