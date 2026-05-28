package sweetgirl.croissant.admin.dto.response;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import sweetgirl.croissant.store.entity.Store;

public record AdminNaverThrottledItem(
		Long id,
		String name,
		String address,
		String kakaoPlaceId,
		Instant lastAttemptedAt,
		Instant throttleExpiresAt) {

	public static AdminNaverThrottledItem from(Store store, long retryAfterDays) {
		Instant attemptedAt = store.getNaverMatchedAt();
		Instant expiresAt = attemptedAt != null ? attemptedAt.plus(retryAfterDays, ChronoUnit.DAYS) : null;
		return new AdminNaverThrottledItem(
			store.getId(),
			store.getName(),
			store.getAddress(),
			store.getKakaoPlaceId(),
			attemptedAt,
			expiresAt);
	}
}
