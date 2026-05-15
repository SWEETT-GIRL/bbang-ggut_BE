package sweetgirl.croissant.store.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.dto.projection.DistrictStoreProjection;

public record DistrictStoreResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl,
		List<MenuSummaryDto> topMenus,
		int distanceMeters,
		long subscribeCount) {

	public static DistrictStoreResponse of(DistrictStoreProjection projection, String thumbnailUrl,
			List<MenuSummaryDto> topMenus) {
		return new DistrictStoreResponse(
			projection.getId(),
			projection.getName(),
			projection.getAddress(),
			thumbnailUrl,
			topMenus,
			(int)Math.round(projection.getDistanceMeters()),
			projection.getSubscribeCount());
	}
}
