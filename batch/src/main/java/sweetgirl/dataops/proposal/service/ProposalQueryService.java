package sweetgirl.dataops.proposal.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.ObjectProvider;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.dto.response.LocationGateResponse;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.policy.LocationProposalGate;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProposalQueryService {

	private final ChangeProposalRepository changeProposalRepository;
	private final LocationProposalGate locationProposalGate;
	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final ObjectMapper objectMapper;

	/**
	 * 위치 제안 게이트 미리보기 — 승인 직전과 같은 판정을 하되 점유·운영 쓰기를 하지 않는다.
	 * 보류된 주소 제안이 왜 막혔는지, 승인하면 어떤 법정동이 함께 쓰이는지를 승인 전에 확인한다.
	 */
	public LocationGateResponse previewLocationGate(Long proposalId) {
		ChangeProposal proposal = changeProposalRepository.findById(proposalId)
			.orElseThrow(() -> new ProposalException(ProposalErrorCode.PROPOSAL_NOT_FOUND));
		if (!locationProposalGate.handles(proposal.getTarget())) {
			return new LocationGateResponse(proposalId, proposal.getTarget(), true, null, null);
		}
		JsonNode newValue = proposal.getNewValue() == null
			? NullNode.getInstance() : objectMapper.readTree(proposal.getNewValue());
		try {
			return new LocationGateResponse(proposalId, proposal.getTarget(), true, null,
				locationProposalGate.verify(proposal.getTarget(), proposal.getStoreId(), newValue,
					servingReaderProvider.getIfAvailable()));
		} catch (ProposalException hold) {
			if (hold.getErrorCode() != ProposalErrorCode.LOCATION_EVIDENCE_REQUIRED) {
				throw hold;
			}
			return new LocationGateResponse(proposalId, proposal.getTarget(), false, hold.getMessage(), null);
		}
	}

	public PageResponse<ChangeProposalResponse> getProposals(
			ProposalStatus status, Long storeId, int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		Page<ChangeProposal> proposals;
		if (storeId != null) {
			proposals = changeProposalRepository.findByStoreIdOrderByCreatedAtDesc(storeId, pageable);
		} else {
			proposals = changeProposalRepository.findByStatusOrderByCreatedAtDesc(
				status != null ? status : ProposalStatus.PENDING, pageable);
		}
		return PageResponse.from(proposals.map(ChangeProposalResponse::from));
	}
}
