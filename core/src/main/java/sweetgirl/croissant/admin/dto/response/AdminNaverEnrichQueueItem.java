package sweetgirl.croissant.admin.dto.response;

import java.time.Instant;

import sweetgirl.croissant.store.entity.Store;

public record AdminNaverEnrichQueueItem(
		Long id,
		String name,
		String address,
		String kakaoPlaceId,
		String naverPlaceId,
		Double lat,
		Double lng,
		Long existingMenuCount,
		Long existingPhotoCount,
		Instant lastAttemptedAt) {

	public static AdminNaverEnrichQueueItem from(Store store, long menuCount, long photoCount) {
		Double lat = store.getLocation() != null ? store.getLocation().getY() : null;
		Double lng = store.getLocation() != null ? store.getLocation().getX() : null;
		return new AdminNaverEnrichQueueItem(
			store.getId(),
			store.getName(),
			store.getAddress(),
			store.getKakaoPlaceId(),
			store.getNaverPlaceId(),
			lat,
			lng,
			menuCount,
			photoCount,
			store.getNaverMatchedAt());
	}
}
