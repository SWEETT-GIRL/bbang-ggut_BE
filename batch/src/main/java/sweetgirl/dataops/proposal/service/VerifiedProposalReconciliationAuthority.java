package sweetgirl.dataops.proposal.service;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Deliberately has no default implementation. An implementation must authenticate an explicit
 * reconciliation decision, measure serving/apply-log evidence and prove the old writer is quiescent.
 * Merely observing current == requested new value cannot establish the old operation's outcome.
 */
public interface VerifiedProposalReconciliationAuthority {
	Evidence require(UUID evidenceId,long proposalId,UUID operationId);
	enum Outcome { APPLIED, NOT_APPLIED }
	record Evidence(UUID evidenceId,long proposalId,UUID operationId,Outcome outcome,String authorityEvent,
		Instant validUntil,JsonNode servingEvidence,JsonNode applyLogEvidence,JsonNode writerQuiescenceEvidence,
		String provenanceField) {}
}
