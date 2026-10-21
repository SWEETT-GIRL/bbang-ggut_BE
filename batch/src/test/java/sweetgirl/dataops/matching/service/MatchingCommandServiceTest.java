package sweetgirl.dataops.matching.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.dataops.matching.dto.request.MatchingProposalRequest;
import sweetgirl.dataops.matching.dto.response.MatchingProposalResult;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MatchingCommandServiceTest {

	@Mock
	private ProposalCommandService proposalCommandService;

	private MatchingCommandService matchingCommandService;

	private ChangeProposalResponse pending(long id) {
		return new ChangeProposalResponse(id, 1L, "store.naver_place_id", null, "\"123\"",
			ProvenanceSource.NAVER, null, ProposalStatus.PENDING, null, null, LocalDateTime.now());
	}

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		matchingCommandService = new MatchingCommandService(proposalCommandService, new ObjectMapper());
	}

	@Test
	void createProposals_는_place_id_proposal_을_생성하고_id_를_모은다() {
		given(proposalCommandService.propose(anyLong(), eq("store.naver_place_id"), isNull(),
			anyString(), eq(ProvenanceSource.NAVER), isNull(),
			eq(sweetgirl.dataops.proposal.enums.ProposalProducer.NAVER_MATCHING), isNull(), isNull()))
			.willReturn(pending(10L), pending(11L));

		MatchingProposalResult result = matchingCommandService.createProposals(
			new MatchingProposalRequest(List.of(
				new MatchingProposalRequest.Item(1L, "111"),
				new MatchingProposalRequest.Item(2L, "222"))));

		assertThat(result.requested()).isEqualTo(2);
		assertThat(result.created()).isEqualTo(2);
		assertThat(result.proposalIds()).containsExactly(10L, 11L);
		// 매칭 제안은 자기 producer 로 들어가야 한다 — 네이버 스냅샷 재판정의 범위 정리에 휩쓸리지 않게 (v3 P1-2)
		then(proposalCommandService).should().propose(
			eq(1L), eq("store.naver_place_id"), isNull(), eq("\"111\""), eq(ProvenanceSource.NAVER), isNull(),
			eq(sweetgirl.dataops.proposal.enums.ProposalProducer.NAVER_MATCHING), isNull(), isNull());
	}
}
