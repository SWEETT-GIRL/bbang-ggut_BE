package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.enums.BreadRecordSort;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.service.BreadRecordLikeCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordSocialQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordSocialController")
class BreadRecordSocialControllerTest {

	private MockMvc mockMvc;

	@Mock
	private BreadRecordSocialQueryService queryService;
	@Mock
	private BreadRecordLikeCommandService likeCommandService;

	@InjectMocks
	private BreadRecordSocialController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("PUT · DELETE /bread-records/{id}/like — 200, 볼 수 없는 기록은 403 BREAD_RECORD403_NOT_VISIBLE")
	void like() throws Exception {
		mockMvc.perform(put("/bread-records/10/like")).andExpect(status().isOk());
		then(likeCommandService).should().like(null, 10L);
		mockMvc.perform(delete("/bread-records/10/like")).andExpect(status().isOk());
		then(likeCommandService).should().unlike(null, 10L);

		willThrow(new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE))
			.given(likeCommandService).like(any(), eq(11L));
		mockMvc.perform(put("/bread-records/11/like"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BREAD_RECORD403_NOT_VISIBLE"));
	}

	@Test
	@DisplayName("GET /members/{id}/bread-records — sort 기본 RECOMMENDED, 잘못된 sort 는 400")
	void memberRecords() throws Exception {
		given(queryService.getMemberRecords(null, 1L, BreadRecordSort.RECOMMENDED, 0, 20))
			.willReturn(new SliceResponse<>(List.of(), false));
		mockMvc.perform(get("/members/1/bread-records"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false));

		given(queryService.getMemberRecords(null, 1L, BreadRecordSort.LATEST, 2, 10))
			.willReturn(new SliceResponse<>(List.of(), false));
		mockMvc.perform(get("/members/1/bread-records").param("sort", "LATEST").param("page", "2").param("size", "10"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/members/1/bread-records").param("sort", "POPULAR"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("GET /bread-records/{id} · /likes")
	void detailAndLikers() throws Exception {
		given(queryService.getDetail(null, 10L))
			.willThrow(new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		mockMvc.perform(get("/bread-records/10")).andExpect(status().isNotFound());

		given(queryService.getLikers(null, 10L, 0, 20)).willReturn(new SliceResponse<>(List.of(), false));
		mockMvc.perform(get("/bread-records/10/likes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isArray());
	}
}
