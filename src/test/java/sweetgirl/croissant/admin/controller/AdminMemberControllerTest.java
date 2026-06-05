package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminMemberRestrictionResponse;
import sweetgirl.croissant.admin.dto.response.AdminProfileResetResponse;
import sweetgirl.croissant.admin.service.AdminMemberService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminMemberControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminMemberService adminMemberService;

	@InjectMocks
	private AdminMemberController adminMemberController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(adminMemberController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void resetProfile_returnsResetResult() throws Exception {
		given(adminMemberService.resetProfile(eq(20L), any()))
			.willReturn(new AdminProfileResetResponse(
				20L,
				"빵긋 사용자",
				100L,
				"부적절한 닉네임",
				"2026-06-05T09:30"));

		mockMvc.perform(post("/api/admin/members/20/profile-reset")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"부적절한 닉네임\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.memberId").value(20))
			.andExpect(jsonPath("$.data.nickname").value("빵긋 사용자"))
			.andExpect(jsonPath("$.data.actionId").value(100));
	}

	@Test
	void createRestriction_returnsRestrictionResult() throws Exception {
		given(adminMemberService.createRestriction(eq(20L), any()))
			.willReturn(new AdminMemberRestrictionResponse(
				200L,
				20L,
				"WRITE_RESTRICTED",
				"최근 90일 내 반복 위반",
				"2026-06-05T09:30",
				"2026-06-12T09:30",
				"2026-06-05T09:30"));

		mockMvc.perform(post("/api/admin/members/20/restrictions")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"WRITE_RESTRICTED\",\"durationDays\":7,\"reason\":\"최근 90일 내 반복 위반\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.restrictionId").value(200))
			.andExpect(jsonPath("$.data.type").value("WRITE_RESTRICTED"))
			.andExpect(jsonPath("$.data.endsAt").value("2026-06-12T09:30"));
	}
}
