package sweetgirl.croissant.store.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.dto.projection.RegionByDistanceProjection;

public record RegionByDistanceResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl,
		List<MenuSummaryDto> topMenus,
		int distanceMeters) {

	public static RegionByDistanceResponse of(RegionByDistanceProjection projection, String thumbnailUrl,
			List<MenuSummaryDto> topMenus) {
		return new RegionByDistanceResponse(
			projection.getId(),
			projection.getName(),
			projection.getAddress(),
			thumbnailUrl,
			topMenus,
			(int)Math.round(projection.getDistanceMeters()));
	}
}
