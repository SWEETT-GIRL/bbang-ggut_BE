package sweetgirl.croissant.store.dto;

import java.time.LocalTime;
import java.util.List;

import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;

public record StoreDetailResponse(
		Long id,
		String name,
		String address,
		LocalTime startTime,
		LocalTime endTime,
		String phone,
		List<String> imageUrls) {
	public static StoreDetailResponse of(Store store, List<StoreImageFile> images) {
		return new StoreDetailResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			store.getStartTime(),
			store.getEndTime(),
			store.getPhone(),
			images.stream().map(StoreImageFile::getAwsKey).toList());
	}
}
