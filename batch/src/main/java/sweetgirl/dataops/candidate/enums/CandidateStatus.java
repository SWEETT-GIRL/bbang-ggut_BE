package sweetgirl.dataops.candidate.enums;

/** 신규 인허가 후보 상태 — 설계 §3.6. status 자체가 워크리스트라 별도 큐가 없다. */
public enum CandidateStatus {
	/** 네이버 매칭 대기 — 워커 매칭 모드의 폴링 대상 */
	WAITING_NAVER,
	/** 네이버 매칭 완료(placeId 확보) — 등록 대기 */
	MATCHED,
	/** 운영 store 등록 완료 (registered_store_id 기록) */
	REGISTERED,
	/** 네이버 검색 부재 — match_attempts 증가 후 재시도 */
	NOT_FOUND,
	/** 비대상·프랜차이즈·오매칭 등 제외 확정 */
	REJECTED
}
