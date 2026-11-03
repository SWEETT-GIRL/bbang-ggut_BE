package sweetgirl.croissant.breadlog.dto.response;

import sweetgirl.croissant.store.dto.projection.NextStoreCandidateProjection;
import sweetgirl.croissant.store.entity.Store;

/**
 * 기록 완료 화면의 "다음 빵집" 한 줄.
 *
 * @param distanceMeters 보낸 좌표에서의 거리. 좌표를 안 보냈으면 null
 * @param reason         SAVED = 담아 뒀는데 아직 기록 없음, NEARBY = 근처에서 공개 빵로그 많은 곳
 */
public record NextStoreResponse(
		Long storeId,
		String name,
		String address,
		Double latitude,
		Double longitude,
		Integer distanceMeters,
		Reason reason) {

	public enum Reason {
		SAVED, NEARBY
	}

	public static NextStoreResponse saved(Store store, Integer distanceMeters) {
		return new NextStoreResponse(store.getId(), store.getName(), store.getAddress(),
			store.getLocation() == null ? null : store.getLocation().getY(),
			store.getLocation() == null ? null : store.getLocation().getX(),
			distanceMeters, Reason.SAVED);
	}

	public static NextStoreResponse nearby(NextStoreCandidateProjection candidate) {
		return new NextStoreResponse(candidate.getId(), candidate.getName(), candidate.getAddress(),
			candidate.getLatitude(), candidate.getLongitude(),
			candidate.getDistanceMeters() == null ? null : (int)Math.round(candidate.getDistanceMeters()),
			Reason.NEARBY);
	}
}
