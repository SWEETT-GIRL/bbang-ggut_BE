package sweetgirl.croissant.admin.dto.response;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreStatus;

public record AdminStoreResponse(
		Long id,
		String name,
		String area,
		String desc,
		String category,
		long alarmCount,
		int rating,
		String address,
		String phone,
		CoordResponse coord,
		String kakaoPlaceId,
		String status,
		List<String> photos,
		int mainPhotoIndex,
		String archivedAt,
		String createdAt,
		Map<String, DayHoursResponse> hours) {

	public record CoordResponse(double latitude, double longitude) {
	}

	public record DayHoursResponse(int openHour, int openMin, int closeHour, int closeMin, boolean closed) {
	}

	private static final Map<DayOfWeek, String> DAY_KEYS = Map.of(
		DayOfWeek.MONDAY, "mon",
		DayOfWeek.TUESDAY, "tue",
		DayOfWeek.WEDNESDAY, "wed",
		DayOfWeek.THURSDAY, "thu",
		DayOfWeek.FRIDAY, "fri",
		DayOfWeek.SATURDAY, "sat",
		DayOfWeek.SUNDAY, "sun");

	public static AdminStoreResponse of(Store store, List<StoreBusinessHour> businessHours,
			List<StoreImageFile> photos, long alarmCount) {
		String area = store.getRegion2nd();
		String statusStr = store.getStatus() == StoreStatus.ACTIVE ? "active" : "inactive";
		String archivedAt = store.getStatus() == StoreStatus.DELETED && store.getUpdatedAt() != null
			? store.getUpdatedAt().toString() : null;

		Map<String, DayHoursResponse> hoursMap = businessHours.stream()
			.filter(bh -> DAY_KEYS.containsKey(bh.getDayOfWeek()))
			.collect(Collectors.toMap(
				bh -> DAY_KEYS.get(bh.getDayOfWeek()),
				bh -> new DayHoursResponse(
					bh.getOpenTime() != null ? bh.getOpenTime().getHour() : 0,
					bh.getOpenTime() != null ? bh.getOpenTime().getMinute() : 0,
					bh.getCloseTime() != null ? bh.getCloseTime().getHour() : 0,
					bh.getCloseTime() != null ? bh.getCloseTime().getMinute() : 0,
					bh.isClosed())));

		List<String> photoUrls = photos.stream()
			.map(StoreImageFile::getAwsKey)
			.toList();

		CoordResponse coord = store.getLocation() != null
			? new CoordResponse(store.getLocation().getY(), store.getLocation().getX()) : null;

		String categoryStr = store.getCategory() != null ? store.getCategory().name() : null;
		return new AdminStoreResponse(
			store.getId(),
			store.getName(),
			area,
			null,
			categoryStr,
			alarmCount,
			0,
			store.getAddress(),
			store.getPhone(),
			coord,
			store.getKakaoPlaceId(),
			statusStr,
			photoUrls,
			0,
			archivedAt,
			store.getCreatedAt() != null ? store.getCreatedAt().toString() : null,
			hoursMap);
	}
}
