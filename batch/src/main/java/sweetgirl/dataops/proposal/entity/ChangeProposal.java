package sweetgirl.dataops.proposal.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.dataops.common.entity.BaseTimeEntity;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.enums.ProposalTargetFamily;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;

/**
 * 자동 반영 불가 diff 의 수납함 — 어드민 승인으로만 운영에 반영된다.
 * 같은 store×target 의 진행 중 제안(PENDING·APPLYING·UNKNOWN)은 1건만 (새 diff 도착 시 기존 PENDING 을 supersede 후 insert).
 *
 * 반영 상태는 두 단계다 (v3 P1-1): 운영 DML 전에 PENDING → APPLYING 으로 점유하고,
 * 결과에 따라 APPROVED / FAILED(운영 미반영 확정) / UNKNOWN(미확정) 으로 확정한다.
 */
@Entity
@Table(name = "change_proposal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeProposal extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(nullable = false, length = 60)
	private String target;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "old_value")
	private String oldValue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "new_value")
	private String newValue;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProvenanceSource source;

	@Column(name = "snapshot_id")
	private Long snapshotId;

	/** 이 제안을 만든 판정 주체 — 재판정 범위의 단위 (v3 P1-2) */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private ProposalProducer producer;

	/** target 에서 결정되는 묶음 — 판정이 다루지 않은 묶음은 그 판정의 정리 대상이 아니다 */
	@Enumerated(EnumType.STRING)
	@Column(name = "target_family", nullable = false, length = 30)
	private ProposalTargetFamily targetFamily;

	/** 판정에 쓴 정책의 버전 — 정책이 바뀌었는데 결과가 같은 경우와 판정을 안 한 경우를 가른다 */
	@Column(name = "policy_version", length = 60)
	private String policyVersion;

	/** 이 제안을 만든 재판정 실행 — 같은 실행에서 살아남은 제안을 범위 정리에서 제외한다 */
	@Column(name = "reconcile_run_id")
	private UUID reconcileRunId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProposalStatus status;

	@Column(name = "review_note", length = 500)
	private String reviewNote;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	/** 반영 시도 식별자 — apply_log·운영 값 대사의 연결 키. 점유 시 발급한다 */
	@Column(name = "operation_id")
	private UUID operationId;

	@Column(name = "apply_started_at")
	private LocalDateTime applyStartedAt;

	/** 기계가 읽는 결과 사유 — CAS 충돌과 제안 만료·미지원 target 을 구분한다 */
	@Column(name = "apply_outcome_reason", length = 40)
	private String applyOutcomeReason;

	@Builder
	public ChangeProposal(Long storeId, String target, String oldValue, String newValue,
			ProvenanceSource source, Long snapshotId, ProposalProducer producer, String policyVersion,
			UUID reconcileRunId) {
		this.storeId = storeId;
		this.target = target;
		this.oldValue = oldValue;
		this.newValue = newValue;
		this.source = source;
		this.snapshotId = snapshotId;
		// producer 를 지어내지 않는다 — 명시하지 않은 호출자는 LEGACY_UNKNOWN 으로 남아 범위 정리 대상에서 빠진다
		this.producer = producer != null ? producer : ProposalProducer.LEGACY_UNKNOWN;
		this.targetFamily = ProposalTargetFamily.of(target);
		this.policyVersion = policyVersion;
		this.reconcileRunId = reconcileRunId;
		this.status = ProposalStatus.PENDING;
	}

	/** 범위 정리 — 이 판정이 다시 봤는데 더 이상 나오지 않는 제안. 삭제가 아니라 세대 교체다 */
	public void expire(String note) {
		transitionFromPending();
		this.status = ProposalStatus.SUPERSEDED;
		this.reviewNote = note;
	}

	/** 다른 producer 의 제안과 같은 target 에서 만난 사실을 남긴다 — 지우지 않고 사람이 보게 한다 */
	public void noteProducerConflict(String note) {
		this.reviewNote = note;
	}

	/**
	 * 운영 DML 직전의 점유 — PENDING 을 소비하는 유일한 반영 전이다.
	 * 이 전이가 운영 쓰기보다 먼저 commit 돼야 중복 승인 두 요청 중 하나만 쓴다.
	 */
	public void beginApply(UUID operationId) {
		transitionFromPending();
		this.status = ProposalStatus.APPLYING;
		this.operationId = operationId;
		this.applyStartedAt = LocalDateTime.now();
	}

	/**
	 * 운영 반영 성공 확정 — note 는 반영 방식이 제안과 달랐을 때만 (예: menu.add 가 기존 메뉴 갱신으로 흡수).
	 */
	public void completeApply(String note) {
		transitionFromApplying();
		this.status = ProposalStatus.APPROVED;
		this.reviewedAt = LocalDateTime.now();
		if (note != null) {
			this.reviewNote = note;
		}
	}

	/** 운영에 아무것도 쓰지 않은 채 끝났다 — 운영은 시도 전과 같다 */
	public void failApply(String reason, String note) {
		transitionFromApplying();
		this.status = ProposalStatus.FAILED;
		this.applyOutcomeReason = reason;
		this.reviewNote = note;
		this.reviewedAt = LocalDateTime.now();
	}

	/** 반영 결과 미확정 — 되돌리지 않는다. 동일 제안은 PENDING 조건의 재점유 대상에서 제외된다. */
	public void markApplyUnknown(String reason, String note) {
		transitionFromApplying();
		this.status = ProposalStatus.UNKNOWN;
		this.applyOutcomeReason = reason;
		this.reviewNote = note;
		this.reviewedAt = LocalDateTime.now();
	}

	/** Only the trusted reconciliation service may call this after storing immutable measured evidence. */
	public void resolveApply(boolean applied,String note) {
		if(this.status!=ProposalStatus.APPLYING && this.status!=ProposalStatus.UNKNOWN)
			throw new ProposalException(ProposalErrorCode.PROPOSAL_STATUS_TRANSITION_NOT_ALLOWED);
		this.status=applied ? ProposalStatus.APPROVED : ProposalStatus.FAILED;
		this.applyOutcomeReason=applied ? "RECONCILED_APPLIED" : "RECONCILED_NOT_APPLIED";
		this.reviewNote=note;this.reviewedAt=LocalDateTime.now();
	}

	public void reject(String note) {
		transitionFromPending();
		this.status = ProposalStatus.REJECTED;
		this.reviewNote = note;
		this.reviewedAt = LocalDateTime.now();
	}

	public void supersede() {
		transitionFromPending();
		this.status = ProposalStatus.SUPERSEDED;
	}

	private void transitionFromPending() {
		if (this.status != ProposalStatus.PENDING) {
			throw new ProposalException(
				ProposalErrorCode.PROPOSAL_STATUS_TRANSITION_NOT_ALLOWED,
				String.format("현재 상태 %s — PENDING 만 처리 가능합니다.", this.status));
		}
	}

	private void transitionFromApplying() {
		if (this.status != ProposalStatus.APPLYING) {
			throw new ProposalException(
				ProposalErrorCode.PROPOSAL_STATUS_TRANSITION_NOT_ALLOWED,
				String.format("현재 상태 %s — 점유(APPLYING)한 제안만 결과를 확정할 수 있습니다.", this.status));
		}
	}
}
