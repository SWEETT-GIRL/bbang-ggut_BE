package sweetgirl.dataops.reconcile.enums;

public enum ReconcileMode {
	/** 2단계 — 모든 diff 를 proposal 로 (자동 반영 0) */
	PROPOSAL_ONLY,
	/** 3단계 — 설계서 5장 매트릭스대로 안전 필드 자동 */
	MATRIX
}
