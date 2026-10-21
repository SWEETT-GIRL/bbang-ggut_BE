package sweetgirl.dataops.proposal.dto.response;

import java.time.LocalDateTime;

import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;

public record ChangeProposalResponse(
		Long proposalId,
		Long storeId,
		String target,
		String oldValue,
		String newValue,
		ProvenanceSource source,
		Long snapshotId,
		ProposalStatus status,
		String reviewNote,
		LocalDateTime reviewedAt,
		LocalDateTime createdAt) {

	public static ChangeProposalResponse from(ChangeProposal proposal) {
		return new ChangeProposalResponse(
			proposal.getId(),
			proposal.getStoreId(),
			proposal.getTarget(),
			proposal.getOldValue(),
			proposal.getNewValue(),
			proposal.getSource(),
			proposal.getSnapshotId(),
			proposal.getStatus(),
			proposal.getReviewNote(),
			proposal.getReviewedAt(),
			proposal.getCreatedAt());
	}
}
