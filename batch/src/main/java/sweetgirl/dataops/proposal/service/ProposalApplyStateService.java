package sweetgirl.dataops.proposal.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import sweetgirl.dataops.agentapply.AgentApplyRequest;
import sweetgirl.dataops.agentapply.AgentApplyHold;
import sweetgirl.dataops.agentapply.AgentManifestApplyGate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenanceId;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;

/**
 * 반영 점유·결과 확정의 독립 트랜잭션 (v3 P1-1).
 *
 * 모든 전이를 REQUIRES_NEW 로 즉시 commit 하는 이유:
 * <ul>
 *   <li>점유는 운영 DML 보다 먼저 확정돼야 한다 — 같은 제안에 동시 승인 두 요청이 오면 하나만 쓰게 만드는 잠금이다</li>
 *   <li>결과는 승인 요청이 rollback 돼도 남아야 한다 — 미확정 건이 PENDING 으로 되살아나면 운영에 이미
 *       반영됐을 수 있는 대상을 다시 쓴다. 되돌리지 않고 UNKNOWN 으로 세워 두는 것이 목적이다</li>
 * </ul>
 * 성공 확정은 상태와 provenance 를 한 트랜잭션에 묶는다 (설계서 4.2 의 ③ 단위).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalApplyStateService {

	private final ChangeProposalRepository changeProposalRepository;
	private final StoreFieldProvenanceRepository storeFieldProvenanceRepository;
	private AgentManifestApplyGate manifestApplyGate;

	@Autowired(required = false)
	public void configureManifestApplyGate(AgentManifestApplyGate gate) { this.manifestApplyGate = gate; }

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID claimExactManifest(Long proposalId, AgentApplyRequest request) {
		if (manifestApplyGate == null) throw new AgentApplyHold("MANIFEST_APPLY_RUNNER_REQUIRED");
		ChangeProposal proposal = changeProposalRepository.findForApplyUpdate(proposalId)
			.orElseThrow(() -> new ProposalException(ProposalErrorCode.PROPOSAL_NOT_FOUND));
		manifestApplyGate.verifyClaim(proposal, request);
		UUID operation = claimPending(proposalId);
		manifestApplyGate.recordClaim(proposal, request, operation);
		return operation;
	}

	/**
	 * 운영 DML 전 점유 — PENDING 만 통과한다. 이미 APPLYING/APPROVED/FAILED/UNKNOWN/REJECTED/SUPERSEDED 면
	 * 현재 상태를 실어 거부한다(중복 요청·거절 제안 재승인·이미 반영된 건).
	 *
	 * @return 이 시도의 operation 식별자 — apply_log 와 운영 값 대사의 연결 키
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID claim(Long proposalId) {
		if (manifestApplyGate != null) throw new AgentApplyHold("PLAIN_APPROVAL_DISABLED_IN_MANIFEST_RUNNER");
		// Immutable CREATED lineage is committed with the new proposal; ordinary writer processes
		// must enforce this too. REUSED retains the pre-existing proposal's manual authority.
		if (changeProposalRepository.requiresAgentManifest(proposalId))
			throw new AgentApplyHold("AGENT_CREATED_PROPOSAL_REQUIRES_MANIFEST");
		return claimPending(proposalId);
	}

	private UUID claimPending(Long proposalId) {
		if (changeProposalRepository.hasUnresolvedTarget(proposalId))
			throw new ProposalException(ProposalErrorCode.PROPOSAL_TARGET_UNRESOLVED);
		UUID operationId = UUID.randomUUID();
		int claimed = changeProposalRepository.claimForApply(proposalId, operationId, LocalDateTime.now());
		if (claimed == 0) {
			ChangeProposal current = changeProposalRepository.findById(proposalId)
				.orElseThrow(() -> new ProposalException(ProposalErrorCode.PROPOSAL_NOT_FOUND));
			throw new ProposalException(ProposalErrorCode.PROPOSAL_NOT_CLAIMABLE,
				String.format("현재 상태 %s — 반영 대기(PENDING) 제안만 승인할 수 있습니다.", current.getStatus()));
		}
		return operationId;
	}

	/** 성공 확정 — 상태와 provenance 를 함께 남긴다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void settleApplied(Long proposalId, String provenanceField, String note) {
		ChangeProposal proposal = load(proposalId);
		proposal.completeApply(note);
		upsertProvenance(proposal, provenanceField);
	}

	/** 운영 미반영 확정 — 운영은 시도 전과 같다. provenance 는 남기지 않는다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void settleFailed(Long proposalId, String reason, String note) {
		load(proposalId).failApply(reason, truncate(note));
	}

	/**
	 * 미확정 확정 — 운영에 문장을 보냈으나 결과를 모른다. PENDING 으로 되돌리지 않는다.
	 * provenance 는 남기지 않는다 — 반영됐는지 모르는 값을 원천 기록으로 세울 수 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void settleUnknown(Long proposalId, String reason, String note) {
		load(proposalId).markApplyUnknown(reason, truncate(note));
		log.error("반영 결과 미확정 — proposal={}, reason={}, note={}", proposalId, reason, note);
	}

	/** Joins the explicit reconciliation transaction so evidence and fence release commit together. */
	@Transactional(propagation=Propagation.MANDATORY)
	public void settleReconciled(Long proposalId,VerifiedProposalReconciliationAuthority.Outcome outcome,String field,String event) {
		var proposal=load(proposalId);
		boolean applied=outcome==VerifiedProposalReconciliationAuthority.Outcome.APPLIED;
		proposal.resolveApply(applied,truncate("대사 근거: "+event));
		if(applied)upsertProvenance(proposal,field);
	}

	private ChangeProposal load(Long proposalId) {
		return changeProposalRepository.findById(proposalId)
			.orElseThrow(() -> new ProposalException(ProposalErrorCode.PROPOSAL_NOT_FOUND));
	}

	private void upsertProvenance(ChangeProposal proposal, String field) {
		StoreFieldProvenance existing = storeFieldProvenanceRepository
			.findById(new StoreFieldProvenanceId(proposal.getStoreId(), field))
			.orElse(null);
		if (existing != null) {
			existing.updateSource(proposal.getSource(), proposal.getSnapshotId());
			return;
		}
		storeFieldProvenanceRepository.save(StoreFieldProvenance.builder()
			.storeId(proposal.getStoreId())
			.field(field)
			.source(proposal.getSource())
			.snapshotId(proposal.getSnapshotId())
			.build());
	}

	/** review_note 는 varchar(500) — 예외 메시지가 그보다 길 수 있다 */
	private String truncate(String note) {
		if (note == null || note.length() <= 500) {
			return note;
		}
		return note.substring(0, 500);
	}
}
