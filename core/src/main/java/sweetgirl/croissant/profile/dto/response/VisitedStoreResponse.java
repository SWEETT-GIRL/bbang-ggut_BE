package sweetgirl.croissant.profile.dto.response;

import java.util.List;

/** 다녀간 빵집 지도의 핀 하나 — 그 사람의 공개 빵로그가 있는 가게. */
public record VisitedStoreResponse(
		Long storeId,
		String storeName,
		Double latitude,
		Double longitude,
		/** 보는 사람에게 보이는 공개 빵로그 수 */
		long publicRecordCount,
		/** 가장 최근 공개 빵로그의 첫 사진(리사이즈 320, 없으면 원본 키). 사진 없는 기록뿐이면 null */
		String thumbnailKey,
		/** 그 가게에서 가장 많이 적은 빵 이름 최대 2개 */
		List<String> topBreads) {
}
