package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.breadlog.dto.response.BreadRecordDetailResponse;
import sweetgirl.croissant.breadlog.dto.response.MemberBreadRecordResponse;
import sweetgirl.croissant.breadlog.service.BreadRecordCompanionService;
import sweetgirl.croissant.breadlog.service.BreadRecordImportService;
import sweetgirl.croissant.breadlog.service.BreadRecordLikeCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordSocialQueryService;
import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.profile.dto.response.AuthorCardResponse;

/** 빵로그 상세 · 프로필 목록 응답 모양을 고정한다. 앱 사용처(FE F1 · F3)가 붙으면 파일:라인을 적는다. 필드는 더하기만. */
@ContextConfiguration(classes = BreadRecordSocialController.class)
class BreadRecordSocialResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private BreadRecordSocialQueryService queryService;
	@MockitoBean
	private BreadRecordLikeCommandService likeCommandService;
	@MockitoBean
	private BreadRecordCompanionService companionService;
	@MockitoBean
	private BreadRecordImportService importService;

	@Test
	void detail_keepsFieldsTheAppReads() throws Exception {
		AuthorCardResponse author = new AuthorCardResponse(1L, "작성자", "dog", "world/dog/front.png", 12,
			"성수동 빵순이", false, true, false);
		given(queryService.getDetail(any(), any())).willReturn(new BreadRecordDetailResponse(
			10L, author, 5L, "밀도 성수", "서울 성동구", "서울_성동구_성수동", "소금빵 최고", List.of("소금빵"), "소금빵",
			LocalDateTime.of(2026, 10, 1, 12, 0), true, true, List.of(), List.of(), 3, true, 0, null, List.of(), null));

		mockMvc.perform(get("/bread-records/10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").isNumber())
			.andExpect(jsonPath("$.data.author.memberId").isNumber())
			.andExpect(jsonPath("$.data.author.nickname").isString())
			.andExpect(jsonPath("$.data.author.characterCode").isString())
			.andExpect(jsonPath("$.data.author.publicRecordCount").isNumber())
			.andExpect(jsonPath("$.data.author.followedByMe").isBoolean())
			.andExpect(jsonPath("$.data.author.followsMe").isBoolean())
			.andExpect(jsonPath("$.data.author.isMe").isBoolean())
			.andExpect(jsonPath("$.data.storeName").isString())
			.andExpect(jsonPath("$.data.memo").isString())
			.andExpect(jsonPath("$.data.lovedBreads").isArray())
			.andExpect(jsonPath("$.data.eatenAt").isString())
			.andExpect(jsonPath("$.data.isPublic").isBoolean())
			.andExpect(jsonPath("$.data.photos").isArray())
			.andExpect(jsonPath("$.data.tags").isArray())
			.andExpect(jsonPath("$.data.likeCount").isNumber())
			.andExpect(jsonPath("$.data.likedByMe").isBoolean())
			.andExpect(jsonPath("$.data.commentCount").isNumber())
			.andExpect(jsonPath("$.data.companions").isArray());
	}

	@Test
	void memberRecords_keepsFieldsTheAppReads() throws Exception {
		given(queryService.getMemberRecords(any(), any(), any(), anyInt(), anyInt()))
			.willReturn(new SliceResponse<>(List.of(new MemberBreadRecordResponse(10L, 5L, "밀도 성수", "서울_성동구_성수동",
				"소금빵 최고", List.of("소금빵"), LocalDateTime.of(2026, 10, 1, 12, 0), true, List.of(), List.of(), 3, 0, false,
				null, true)), true));

		mockMvc.perform(get("/members/1/bread-records"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").isNumber())
			.andExpect(jsonPath("$.data.content[0].storeName").isString())
			.andExpect(jsonPath("$.data.content[0].photos").isArray())
			.andExpect(jsonPath("$.data.content[0].likeCount").isNumber())
			.andExpect(jsonPath("$.data.content[0].commentCount").isNumber())
			.andExpect(jsonPath("$.data.content[0].likedByMe").isBoolean())
			.andExpect(jsonPath("$.data.content[0].eatenAt").isString())
			.andExpect(jsonPath("$.data.hasNext").isBoolean());
	}
}
