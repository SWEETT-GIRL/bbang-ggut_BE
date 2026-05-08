package sweetgirl.croissant.store.dto;

import java.time.LocalTime;
import java.util.List;

import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;

public record StoreDetailResponse(
		Long id,
		String name,
		String address,
		List<BusinessHourResponse> businessHours,
		String phone,
		List<String> imageUrls) {

	public record BusinessHourResponse(
			String dayOfWeek,
			LocalTime openTime,
			LocalTime closeTime,
			boolean isClosed) {
	}

	public static StoreDetailResponse of(Store store, List<StoreBusinessHour> businessHours,
			List<StoreImageFile> images) {
		return new StoreDetailResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			businessHours.stream()
				.map(bh -> new BusinessHourResponse(
					bh.getDayOfWeek().name(),
					bh.getOpenTime(),
					bh.getCloseTime(),
					bh.isClosed()))
				.toList(),
			store.getPhone(),
			images.stream().map(StoreImageFile::getAwsKey).toList());
	}
}
