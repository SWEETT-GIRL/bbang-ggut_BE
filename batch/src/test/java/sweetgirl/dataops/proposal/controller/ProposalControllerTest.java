package sweetgirl.dataops.proposal.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

import sweetgirl.dataops.global.advice.GlobalExceptionHandler;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.dto.response.LocationGateResponse;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.proposal.service.ProposalQueryService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.serving.code.ServingErrorCode;
import sweetgirl.dataops.serving.exception.ServingException;

@ExtendWith(MockitoExtension.class)
class ProposalControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ProposalCommandService proposalCommandService;
	@Mock
	private ProposalQueryService proposalQueryService;

	@InjectMocks
	private ProposalController proposalController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(proposalController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private ChangeProposalResponse createResponse(ProposalStatus status) {
		return new ChangeProposalResponse(
			5L, 1L, "store.phone", "\"02-1\"", "\"02-2\"", ProvenanceSource.NAVER, 7L,
			status, null, null, LocalDateTime.now());
	}

	@Test
	void getProposals_기본은_PENDING_목록이다() throws Exception {
		given(proposalQueryService.getProposals(isNull(), isNull(), eq(0), eq(20)))
			.willReturn(new PageResponse<>(List.of(createResponse(ProposalStatus.PENDING)), 0, 20, 1, 1));

		mockMvc.perform(get("/api/proposals"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].target").value("store.phone"))
			.andExpect(jsonPath("$.data.content[0].snapshotId").value(7));
	}

	@Test
	void locationGate_는_승인하면_쓰게_될_지역과_근거를_보여_준다() throws Exception {
		given(proposalQueryService.previewLocationGate(9L)).willReturn(new LocationGateResponse(9L, "store.address",
			true, null, new sweetgirl.dataops.region.dto.LocationEvidence(
				138L, "4131010300", "경기", "구리시", "인창동", "mois-stanregin-api-20260926")));

		mockMvc.perform(get("/api/proposals/9/location-gate"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.passed").value(true))
			.andExpect(jsonPath("$.data.evidence.region3rd").value("인창동"))
			.andExpect(jsonPath("$.data.evidence.resolutionId").value(138));
		// 미리보기는 승인이 아니다 — 명령 서비스(점유·운영 쓰기)를 부르지 않는다
		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void locationGate_는_보류_사유를_돌려준다() throws Exception {
		given(proposalQueryService.previewLocationGate(9L)).willReturn(new LocationGateResponse(9L, "store.address",
			false, "새 좌표의 법정동 해소 근거가 없습니다", null));

		mockMvc.perform(get("/api/proposals/9/location-gate"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.passed").value(false))
			.andExpect(jsonPath("$.data.holdReason").value("새 좌표의 법정동 해소 근거가 없습니다"));
	}

	@Test
	void approve_반영_결과를_반환한다() throws Exception {
		given(proposalCommandService.approve(5L)).willReturn(createResponse(ProposalStatus.APPROVED));

		mockMvc.perform(patch("/api/proposals/5/approve"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("APPROVED"));
	}

	@Test
	void approve_운영_쓰기_미설정이면_503_이다() throws Exception {
		given(proposalCommandService.approve(5L))
			.willThrow(new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED));

		mockMvc.perform(patch("/api/proposals/5/approve"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("PROD_DB_NOT_CONFIGURED"));
	}

	@Test
	void reject_사유_없이_요청하면_400_이다() throws Exception {
		mockMvc.perform(patch("/api/proposals/5/reject")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void reject_사유와_함께_거절한다() throws Exception {
		given(proposalCommandService.reject(5L, "오매칭 의심")).willReturn(createResponse(ProposalStatus.REJECTED));

		mockMvc.perform(patch("/api/proposals/5/reject")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"note\": \"오매칭 의심\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REJECTED"));
	}
}
