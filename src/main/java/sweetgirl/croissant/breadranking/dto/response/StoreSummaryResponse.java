package sweetgirl.croissant.breadranking.dto.response;

import sweetgirl.croissant.store.RegionFormatter;
import sweetgirl.croissant.store.entity.Store;

/** 순위·후보 줄의 가게 요약 — 이름·사진·동네. */
public record StoreSummaryResponse(Long storeId, String name, String thumbnailImageKey, String region) {

	public static StoreSummaryResponse of(Store store, String thumbnailImageKey) {
		return new StoreSummaryResponse(
			store.getId(),
			store.getName(),
			thumbnailImageKey,
			RegionFormatter.full(store.getRegion1st(), store.getRegion2nd(), store.getRegion3rd()));
	}
}
