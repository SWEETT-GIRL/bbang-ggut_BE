package sweetgirl.dataops.reconcile.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.enums.ReconcileMode;

/**
 * 병합 정책 (설계서 5장) — 필드 × 출처 → AUTO | PROPOSAL | IGNORE.
 * 출처 우선순위 ADMIN > USER > NAVER > LOCALDATA: 상위 출처가 기록된 필드에 하위 출처 diff 는
 * AUTO 대상이라도 proposal 로 강등한다. 2단계는 mode=PROPOSAL_ONLY 로 전 필드 proposal.
 */
@Component
public class MergePolicy {

	@Value("${dataops.reconcile.mode:PROPOSAL_ONLY}")
	private ReconcileMode mode;

	public MergeDecision decide(String target, ProvenanceSource diffSource, ProvenanceSource existingSource) {
		if (existingSource != null && existingSource.ordinal() < diffSource.ordinal()) {
			return MergeDecision.PROPOSAL;
		}
		if (mode == ReconcileMode.PROPOSAL_ONLY) {
			return MergeDecision.PROPOSAL;
		}
		return matrixDecision(target);
	}

	/** 5장 매트릭스 — 안전 필드만 AUTO (3단계에서 mode=MATRIX 전환 시 발효) */
	private MergeDecision matrixDecision(String target) {
		if ("store.business_hours".equals(target) || "store.phone".equals(target)
			|| "store.instagram_handle".equals(target)) {
			return MergeDecision.AUTO;
		}
		if (target.startsWith("menu.add.")) {
			return MergeDecision.AUTO;
		}
		if (target.matches("^menu\\.\\d+\\.price$")) {
			return MergeDecision.AUTO;
		}
		return MergeDecision.PROPOSAL;
	}
}
