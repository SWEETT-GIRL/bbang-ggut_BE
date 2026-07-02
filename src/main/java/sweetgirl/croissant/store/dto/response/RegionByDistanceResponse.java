package sweetgirl.croissant.store.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.dto.projection.RegionByDistanceProjection;

public record RegionByDistanceResponse(
		Long id,
		String name,
		String address,
		String thumbnailImageKey,
		List<MenuSummaryDto> topMenus,
		int distanceMeters) {

	public static RegionByDistanceResponse of(RegionByDistanceProjection projection, String thumbnailImageKey,
			List<MenuSummaryDto> topMenus) {
		return new RegionByDistanceResponse(
			projection.getId(),
			projection.getName(),
			projection.getAddress(),
			thumbnailImageKey,
			topMenus,
			(int)Math.round(projection.getDistanceMeters()));
	}
}
