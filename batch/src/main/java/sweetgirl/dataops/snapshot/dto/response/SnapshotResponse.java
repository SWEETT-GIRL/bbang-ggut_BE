package sweetgirl.dataops.snapshot.dto.response;

import java.time.LocalDateTime;

import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.sync.enums.SyncSource;

/** 목록용 — payload 는 부피가 커서 단건 조회(SnapshotDetailResponse)에서만 내려준다 */
public record SnapshotResponse(
		Long snapshotId,
		Long storeId,
		SyncSource source,
		LocalDateTime fetchedAt,
		String contentHash) {

	public static SnapshotResponse from(PlaceSnapshot snapshot) {
		return new SnapshotResponse(
			snapshot.getId(),
			snapshot.getStoreId(),
			snapshot.getSource(),
			snapshot.getFetchedAt(),
			snapshot.getContentHash());
	}
}
