package sweetgirl.croissant.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.response.BlockListResponse;
import sweetgirl.croissant.member.dto.response.BlockResponse;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberBlockService;

@ExtendWith(MockitoExtension.class)
class MemberBlockControllerTest {

	private MockMvc mockMvc;

	@Mock
	private MemberBlockService memberBlockService;

	@InjectMocks
	private MemberBlockController memberBlockController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(memberBlockController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void blockMember_returns201WithBlockedMember() throws Exception {
		given(memberBlockService.blockMember(any(), any()))
			.willReturn(new BlockResponse(2L, "대상자", LocalDateTime.of(2026, 6, 5, 10, 0)));

		mockMvc.perform(post("/blocks")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"blockedMemberId\":2}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.memberId").value(2))
			.andExpect(jsonPath("$.data.nickname").value("대상자"));
	}

	@Test
	void blockMember_returns400WhenSelfTarget() throws Exception {
		given(memberBlockService.blockMember(any(), any()))
			.willThrow(new MemberException(MemberErrorCode.MEMBER_BLOCK_SELF_NOT_ALLOWED));

		mockMvc.perform(post("/blocks")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"blockedMemberId\":1}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEMBER_BLOCK_SELF_NOT_ALLOWED"));
	}

	@Test
	void unblockMember_returns200() throws Exception {
		willDoNothing().given(memberBlockService).unblockMember(any(), any());

		mockMvc.perform(delete("/blocks/2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void getBlocks_returns200WithBlockList() throws Exception {
		given(memberBlockService.getBlocks(any(), any(Integer.class), any(Integer.class)))
			.willReturn(new BlockListResponse(
				List.of(new BlockResponse(2L, "대상자", LocalDateTime.of(2026, 6, 5, 10, 0))),
				false));

		mockMvc.perform(get("/blocks")
			.param("page", "0")
			.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].memberId").value(2))
			.andExpect(jsonPath("$.data.content[0].nickname").value("대상자"))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}
}
