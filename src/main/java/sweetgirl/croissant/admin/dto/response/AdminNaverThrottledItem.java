package sweetgirl.croissant.admin.dto.response;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import sweetgirl.croissant.store.entity.Store;

public record AdminNaverThrottledItem(
		Long id,
		String name,
		String address,
		String kakaoPlaceId,
		Double lat,
		Double lng,
		Long existingMenuCount,
		Instant lastAttemptedAt,
		Instant throttleExpiresAt) {

	public static AdminNaverThrottledItem from(Store store, long menuCount, long retryAfterDays) {
		Instant attemptedAt = store.getNaverMatchedAt();
		Instant expiresAt = attemptedAt != null ? attemptedAt.plus(retryAfterDays, ChronoUnit.DAYS) : null;
		Double lat = store.getLocation() != null ? store.getLocation().getY() : null;
		Double lng = store.getLocation() != null ? store.getLocation().getX() : null;
		return new AdminNaverThrottledItem(
			store.getId(),
			store.getName(),
			store.getAddress(),
			store.getKakaoPlaceId(),
			lat,
			lng,
			menuCount,
			attemptedAt,
			expiresAt);
	}
}
