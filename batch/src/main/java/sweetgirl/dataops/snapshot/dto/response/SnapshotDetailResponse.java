package sweetgirl.dataops.snapshot.dto.response;

import java.time.LocalDateTime;

import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;

public record SnapshotDetailResponse(
		Long snapshotId,
		Long storeId,
		SyncSource source,
		LocalDateTime fetchedAt,
		String contentHash,
		JsonNode payload) {

	public static SnapshotDetailResponse of(PlaceSnapshot snapshot, JsonNode payload) {
		return new SnapshotDetailResponse(
			snapshot.getId(),
			snapshot.getStoreId(),
			snapshot.getSource(),
			snapshot.getFetchedAt(),
			snapshot.getContentHash(),
			payload);
	}
}
