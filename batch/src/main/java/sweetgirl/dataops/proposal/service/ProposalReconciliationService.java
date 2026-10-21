package sweetgirl.dataops.proposal.service;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sweetgirl.dataops.agentapply.AgentApplyHold;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Non-HTTP explicit reconciliation entry; no serving writes and no automatic reset/retry. */
@Service
public class ProposalReconciliationService {
	private final ChangeProposalRepository proposals;
	private final ProposalApplyStateService state;
	private final ObjectProvider<VerifiedProposalReconciliationAuthority> authorities;
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	public ProposalReconciliationService(ChangeProposalRepository proposals,ProposalApplyStateService state,
			ObjectProvider<VerifiedProposalReconciliationAuthority> authorities,DataSource source,ObjectMapper mapper) {
		this.proposals=proposals;this.state=state;this.authorities=authorities;this.jdbc=new JdbcTemplate(source);this.mapper=mapper;
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public void resolve(long proposalId,UUID evidenceId) {
		var authority=authorities.getIfAvailable();
		if(authority==null)throw new AgentApplyHold("TRUSTED_RECONCILIATION_AUTHORITY_REQUIRED");
		var proposal=proposals.findForApplyUpdate(proposalId)
			.orElseThrow(()->new ProposalException(ProposalErrorCode.PROPOSAL_NOT_FOUND));
		if(proposal.getStatus()!=ProposalStatus.UNKNOWN && proposal.getStatus()!=ProposalStatus.APPLYING)
			throw new AgentApplyHold("PROPOSAL_NOT_UNRESOLVED");
		var proof=authority.require(evidenceId,proposalId,proposal.getOperationId());
		if(proof==null || !evidenceId.equals(proof.evidenceId()) || proof.proposalId()!=proposalId
			|| !proposal.getOperationId().equals(proof.operationId()) || proof.outcome()==null
			|| proof.validUntil()==null || !proof.validUntil().isAfter(Instant.now())
			|| proof.authorityEvent()==null || proof.authorityEvent().isBlank())
			throw new AgentApplyHold("RECONCILIATION_EVIDENCE_MISMATCH");
		String serving=digest(proof.servingEvidence()),log=digest(proof.applyLogEvidence()),quiescence=digest(proof.writerQuiescenceEvidence());
		if(proof.outcome()==VerifiedProposalReconciliationAuthority.Outcome.APPLIED
			&& (proof.provenanceField()==null || !proof.provenanceField().matches("[A-Za-z][A-Za-z0-9_.-]{0,59}")))
			throw new AgentApplyHold("RECONCILIATION_PROVENANCE_REQUIRED");
		jdbc.update("""
			INSERT INTO proposal_apply_reconciliation(evidence_id,proposal_id,operation_id,outcome,authority_event,
			serving_evidence_digest,apply_log_evidence_digest,writer_quiescence_digest,evidence)
			VALUES(?,?,?,?,?,?,?,?,?::jsonb)
			""",evidenceId,proposalId,proof.operationId(),proof.outcome().name(),proof.authorityEvent(),serving,log,quiescence,
			mapper.writeValueAsString(proof));
		state.settleReconciled(proposalId,proof.outcome(),proof.provenanceField(),proof.authorityEvent());
	}
	private String digest(JsonNode evidence) {
		if(evidence==null || !evidence.isObject() || evidence.isEmpty())throw new AgentApplyHold("RECONCILIATION_EVIDENCE_REQUIRED");
		return ResponseDigests.sha256(ResponseDigests.canonical(evidence,mapper));
	}
}
