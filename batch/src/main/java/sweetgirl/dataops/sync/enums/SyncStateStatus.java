package sweetgirl.dataops.sync.enums;

public enum SyncStateStatus {
	/** 스케줄 대상 */
	ACTIVE,
	/** 수동 지정 — 재크롤 영구 제외 */
	PARKED,
	/** place_id 무효 판명 (연속 실패 10회) */
	UNLINKED
}
