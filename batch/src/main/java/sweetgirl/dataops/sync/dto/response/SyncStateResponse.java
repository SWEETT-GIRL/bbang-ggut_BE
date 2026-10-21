package sweetgirl.dataops.sync.dto.response;

import java.time.LocalDateTime;

import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;

public record SyncStateResponse(
		Long syncStateId,
		Long storeId,
		SyncSource source,
		String externalId,
		SyncTier tier,
		SyncStateStatus state,
		LocalDateTime nextDueAt,
		LocalDateTime lastAttemptAt,
		LocalDateTime lastSuccessAt,
		int visitCount,
		int changeCount,
		int consecutiveFailures) {

	public static SyncStateResponse from(StoreSyncState syncState) {
		return new SyncStateResponse(
			syncState.getId(),
			syncState.getStoreId(),
			syncState.getSource(),
			syncState.getExternalId(),
			syncState.getTier(),
			syncState.getState(),
			syncState.getNextDueAt(),
			syncState.getLastAttemptAt(),
			syncState.getLastSuccessAt(),
			syncState.getVisitCount(),
			syncState.getChangeCount(),
			syncState.getConsecutiveFailures());
	}
}
