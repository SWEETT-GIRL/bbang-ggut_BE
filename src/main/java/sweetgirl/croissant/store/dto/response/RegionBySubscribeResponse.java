package sweetgirl.croissant.store.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.dto.projection.RegionBySubscribeProjection;

public record RegionBySubscribeResponse(
		Long id,
		String name,
		String address,
		String thumbnailImageKey,
		List<MenuSummaryDto> topMenus,
		long subscribeCount) {

	public static RegionBySubscribeResponse of(RegionBySubscribeProjection projection, String thumbnailImageKey,
			List<MenuSummaryDto> topMenus) {
		return new RegionBySubscribeResponse(
			projection.getId(),
			projection.getName(),
			projection.getAddress(),
			thumbnailImageKey,
			topMenus,
			projection.getSubscribeCount());
	}
}
