package sweetgirl.dataops.matching.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.matching.dto.request.MatchingProposalRequest;
import sweetgirl.dataops.matching.dto.response.MatchingProposalResult;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 매칭 반영 — 미매칭 가게에 naver_place_id 를 붙이는 제안을 만든다.
 * reconcile 과 별개 트랙(크롤 결과가 아니라 검색·눈검사 결과)이지만, 운영 반영은 동일하게
 * change_proposal → 승인(apply_log 선기록 → ServingWriter) 경로를 탄다. 승인 후 sync_state 를
 * 생성해야(SyncSeedService) 크롤 궤도에 들어간다 — 그건 반영 확인 뒤 별도 실행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MatchingCommandService {

	private final ProposalCommandService proposalCommandService;
	private final ObjectMapper objectMapper;

	public MatchingProposalResult createProposals(MatchingProposalRequest request) {
		List<Long> proposalIds = new ArrayList<>();
		int skipped = 0;
		for (MatchingProposalRequest.Item item : request.candidates()) {
			ChangeProposalResponse proposal = proposalCommandService.propose(
				item.storeId(), "store.naver_place_id", null,
				objectMapper.writeValueAsString(item.naverPlaceId()),
				ProvenanceSource.NAVER, null,
				// 재판정 범위 표지 — 네이버 스냅샷 재판정이 이 제안을 자기 세대로 오인해 정리하지 않게 한다 (v3 P1-2)
				sweetgirl.dataops.proposal.enums.ProposalProducer.NAVER_MATCHING, null, null);
			// null = 동일 값 거절 이력로 재상정 스킵 (ProposalCommandService.propose)
			if (proposal != null && proposal.status().name().equals("PENDING")) {
				proposalIds.add(proposal.proposalId());
			} else {
				skipped++;
			}
		}
		log.info("매칭 제안 생성 — 요청 {}, 생성 {}, 스킵 {}",
			request.candidates().size(), proposalIds.size(), skipped);
		return new MatchingProposalResult(
			request.candidates().size(), proposalIds.size(), skipped, proposalIds);
	}
}
