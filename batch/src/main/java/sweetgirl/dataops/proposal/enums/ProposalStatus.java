package sweetgirl.dataops.proposal.enums;

public enum ProposalStatus {
	/** 반영 대기 — 자동 정책 검증 또는 명시적 승인을 거친다. 같은 store×target 진행 중 제안은 1건만. */
	PENDING,
	/**
	 * 운영 반영 점유 중 — 운영 DML 직전에 독립 트랜잭션으로 확정한다.
	 * 같은 대상의 중복 승인·새 제안을 막는 자리표이며, 중단되면 사람이 대사할 때까지 이 상태로 남는다.
	 */
	APPLYING,
	/** 승인 → 운영 반영 완료 */
	APPROVED,
	/** 반영 시도가 운영에 아무것도 쓰지 않은 채 끝났다 (CAS 충돌·제안 만료·미지원 target) — 운영은 시도 전과 같다 */
	FAILED,
	/**
	 * 반영 결과 미확정 — 운영에 문장을 보낸 뒤 결과를 확인하지 못했다(응답 유실·프로세스 중단).
	 * PENDING 으로 되돌리지 않는다. 사람이 apply_log ↔ 운영 값을 대사할 때까지 같은 대상을 다시 쓰지 않는다.
	 */
	UNKNOWN,
	/** 거절 (사유 기록) */
	REJECTED,
	/** 같은 대상에 새 diff 도착 — 새 proposal 로 대체됨 */
	SUPERSEDED
}
