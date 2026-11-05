package sweetgirl.croissant.comment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.dto.response.CommunityAgreementResponse;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.comment.service.CommentCommandService;
import sweetgirl.croissant.comment.service.CommentQueryService;
import sweetgirl.croissant.comment.service.CommunityAgreementService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordCommentController")
class BreadRecordCommentControllerTest {

	private MockMvc mockMvc;

	@Mock
	private CommentQueryService commentQueryService;
	@Mock
	private CommentCommandService commentCommandService;
	@Mock
	private CommunityAgreementService communityAgreementService;

	@InjectMocks
	private BreadRecordCommentController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("POST 댓글 — 201, 사유 코드는 그대로 나간다(TOO_FAST 429 · COMMUNITY_AGREEMENT_REQUIRED 403 · LINK 400)")
	void create() throws Exception {
		given(commentCommandService.create(any(), eq(10L), any())).willReturn(new CommentResponse(100L, 2L, "나",
			null, null, "소금빵 최고", List.of(), LocalDateTime.now(), false, true, null, null));
		mockMvc.perform(post("/bread-records/10/comments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵 최고\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").value(100));

		given(commentCommandService.create(any(), eq(11L), any()))
			.willThrow(new CommentException(CommentErrorCode.COMMENT_TOO_FAST));
		mockMvc.perform(post("/bread-records/11/comments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"빵\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("TOO_FAST"));

		given(commentCommandService.create(any(), eq(12L), any()))
			.willThrow(new CommentException(CommentErrorCode.COMMUNITY_AGREEMENT_REQUIRED));
		mockMvc.perform(post("/bread-records/12/comments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"빵\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMMUNITY_AGREEMENT_REQUIRED"));

		mockMvc.perform(post("/bread-records/13/comments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"  \"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("GET 댓글 · 멘션 후보, DELETE 댓글, 규칙 동의 GET · POST")
	void others() throws Exception {
		given(commentQueryService.getComments(null, 10L, 0, 20)).willReturn(new SliceResponse<>(List.of(), false));
		mockMvc.perform(get("/bread-records/10/comments")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isArray());

		given(commentQueryService.mentionCandidates(null, 10L, "빵")).willReturn(List.of());
		mockMvc.perform(get("/bread-records/10/mention-candidates").param("q", "빵")).andExpect(status().isOk());

		mockMvc.perform(delete("/comments/100")).andExpect(status().isOk());
		then(commentCommandService).should().delete(null, 100L);

		given(communityAgreementService.get(null))
			.willReturn(new CommunityAgreementResponse(false, null, null, "2026-10-21"));
		mockMvc.perform(get("/members/me/community-agreement")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.agreed").value(false));
		given(communityAgreementService.agree(null))
			.willReturn(new CommunityAgreementResponse(true, LocalDateTime.now(), "2026-10-21", "2026-10-21"));
		mockMvc.perform(post("/members/me/community-agreement")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.agreed").value(true));
	}
}
