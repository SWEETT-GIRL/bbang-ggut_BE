package sweetgirl.dataops.sync.dto.response;

import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;

/**
 * 워커 워크리스트 항목 — 워커는 판정하지 않으므로 fetch 에 필요한 최소 정보만 준다.
 * {@code attemptSeq} 는 이 배부의 세대다 (v3 P2-1) — 워커가 제출에 실어 보내면 늦게 도착한 이전 시도를 가려낼 수 있다.
 */
public record DueStoreResponse(
		Long syncStateId,
		Long storeId,
		SyncSource source,
		String externalId,
		long attemptSeq) {

	public static DueStoreResponse from(StoreSyncState syncState) {
		return new DueStoreResponse(
			syncState.getId(),
			syncState.getStoreId(),
			syncState.getSource(),
			syncState.getExternalId(),
			syncState.getAttemptSeq());
	}
}
