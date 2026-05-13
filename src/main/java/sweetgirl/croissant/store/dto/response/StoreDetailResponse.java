package sweetgirl.croissant.store.dto.response;

import java.time.LocalTime;
import java.util.List;

import sweetgirl.croissant.global.dto.ImageResponse;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;

public record StoreDetailResponse(
		Long id,
		String name,
		String address,
		List<BusinessHourResponse> businessHours,
		String phone,
		List<ImageResponse> images,
		List<MenuImageResponse> menuImages,
		boolean isSubscribed) {

	public record BusinessHourResponse(
			String dayOfWeek,
			LocalTime openTime,
			LocalTime closeTime,
			boolean isClosed) {
	}

	public record MenuImageResponse(Long menuId, Long id, String awsKey) {
	}

	public static StoreDetailResponse of(Store store, List<StoreBusinessHour> businessHours,
			List<StoreImageFile> images, List<MenuImageFile> menuImages, boolean isSubscribed) {
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
			images.stream().map(img -> new ImageResponse(img.getId(), img.getAwsKey())).toList(),
			menuImages.stream().map(mi -> new MenuImageResponse(mi.getMenu().getId(), mi.getId(), mi.getAwsKey()))
				.toList(),
			isSubscribed);
	}
}
