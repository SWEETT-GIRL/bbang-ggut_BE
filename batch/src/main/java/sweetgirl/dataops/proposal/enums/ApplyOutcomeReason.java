package sweetgirl.dataops.proposal.enums;

/**
 * 반영 결과 사유 (v3 P1-1) — change_proposal.apply_outcome_reason 에 기록한다.
 * FAILED 는 운영에 아무것도 쓰지 않은 채 끝난 경우, UNKNOWN 은 문장을 보낸 뒤 결과를 확인하지 못한 경우다.
 */
public enum ApplyOutcomeReason {
	/** FAILED — 운영 값이 제안 당시와 다르다. 새 근거로 다시 판정해야 한다 */
	VALUE_DRIFTED,
	/** FAILED — 메뉴 종류가 제안 당시와 다르거나 대상 종류가 운영에 없다 */
	MENU_CATEGORY_CHANGED,
	/** FAILED — 사용자 등록 메뉴이거나 운영 정규화로만 겹치는 다른 이름의 메뉴다 */
	MENU_CONFLICT,
	/** FAILED — 지원하지 않는 반영 대상 */
	TARGET_UNSUPPORTED,
	/** FAILED — 제안 값을 해석할 수 없다 (운영 DML 전) */
	INVALID_PROPOSAL_VALUE,
	/** UNKNOWN — 운영 DB 오류. 문장이 반영됐는지 알 수 없다 */
	SERVING_ERROR,
	/** UNKNOWN — 예상하지 못한 오류로 중단됐다 */
	INTERRUPTED
}
