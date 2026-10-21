package sweetgirl.dataops.candidate.enums;

/**
 * 등록 진행 상태 (v3 P1-3) — {@link CandidateStatus#REGISTERED} 가 "운영 store 가 생겼다"를 뜻하는 것과
 * 별개로, 후속(sync_state·외부 ID·초기 수집 예약·provenance)까지 끝났는지를 따로 남긴다.
 *
 * 둘을 한 값에 담으면 운영에 가게는 있는데 한 번도 수집되지 않은 상태가 "등록 완료"로 보인다
 * (2026-08-25 실측 4건). 복구가 필요한 건은 조회로 찾을 수 있어야 한다.
 */
public enum CandidateRegistrationState {
	/** operation 발급 완료, 운영 결과 미확인 */
	PREPARED,
	/** 운영 store 존재 확인 — 후속은 아직 */
	STORE_PRESENT,
	/** 후속까지 확인 완료 */
	COMPLETED,
	/** 후속이 빠졌다 — 재실행으로 보완해야 한다 */
	NEEDS_RECOVERY,
	/** 운영 결과 불명 — 확인 전에는 다시 쓰지 않는다 */
	UNKNOWN,
	/** 검증된 위치 근거가 없어 신규 INSERT 를 보류했다 */
	HELD_NO_LOCATION_EVIDENCE,
	/** DELETED 가게라 이전 제외 사유 검토가 필요하다 — 자동 재활성하지 않는다 */
	HELD_DELETED_STORE
}
