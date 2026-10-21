package sweetgirl.dataops.signal.dto.response;

import java.time.LocalDateTime;

import sweetgirl.dataops.signal.entity.StoreSignal;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal;

/** 가게 신호 응답 — 저장본(updatedAt 있음)과 미리보기(updatedAt null, 저장 안 함)를 같은 모양으로 돌려준다 */
public record StoreSignalResponse(
		Long storeId,
		Long snapshotId,
		LocalDateTime fetchedAt,
		int parserVersion,
		LocalDateTime updatedAt,
		ParsedStoreSignal signals) {

	public static StoreSignalResponse of(StoreSignal stored, ParsedStoreSignal signals) {
		return new StoreSignalResponse(
			stored.getStoreId(),
			stored.getSnapshotId(),
			stored.getFetchedAt(),
			stored.getParserVersion(),
			stored.getUpdatedAt(),
			signals);
	}

	public static StoreSignalResponse preview(PlaceSnapshot snapshot, int parserVersion,
			ParsedStoreSignal signals) {
		return new StoreSignalResponse(
			snapshot.getStoreId(),
			snapshot.getId(),
			snapshot.getFetchedAt(),
			parserVersion,
			null,
			signals);
	}
}
