package sweetgirl.croissant.comment.controller;

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

import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.service.CommentCommandService;
import sweetgirl.croissant.comment.service.CommentQueryService;
import sweetgirl.croissant.comment.service.CommunityAgreementService;
import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.global.response.SliceResponse;

/** 댓글 응답 모양을 고정한다. 앱 사용처(FE F4 댓글 시트)가 붙으면 파일:라인을 적는다. 필드는 더하기만. */
@ContextConfiguration(classes = BreadRecordCommentController.class)
class CommentResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private CommentQueryService commentQueryService;
	@MockitoBean
	private CommentCommandService commentCommandService;
	@MockitoBean
	private CommunityAgreementService communityAgreementService;

	@Test
	void comments_keepsFieldsTheAppReads() throws Exception {
		given(commentQueryService.getComments(any(), any(), anyInt(), anyInt())).willReturn(new SliceResponse<>(List.of(
			new CommentResponse(100L, 2L, "빵순이", "dog", "world/dog/front.png", "@작성자 맛있어요",
				List.of(new CommentResponse.MentionResponse(1L, 0, 4)), LocalDateTime.of(2026, 10, 22, 9, 0), false,
				true,
				null, null)),
			false));

		mockMvc.perform(get("/bread-records/10/comments"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").isNumber())
			.andExpect(jsonPath("$.data.content[0].memberId").isNumber())
			.andExpect(jsonPath("$.data.content[0].nickname").isString())
			.andExpect(jsonPath("$.data.content[0].characterCode").isString())
			.andExpect(jsonPath("$.data.content[0].content").isString())
			.andExpect(jsonPath("$.data.content[0].mentions[0].memberId").isNumber())
			.andExpect(jsonPath("$.data.content[0].mentions[0].start").isNumber())
			.andExpect(jsonPath("$.data.content[0].mentions[0].length").isNumber())
			.andExpect(jsonPath("$.data.content[0].createdAt").isString())
			.andExpect(jsonPath("$.data.content[0].isAuthor").isBoolean())
			.andExpect(jsonPath("$.data.content[0].isMine").isBoolean())
			.andExpect(jsonPath("$.data.content[0].parentId").doesNotExist())
			.andExpect(jsonPath("$.data.hasNext").isBoolean());
	}
}
