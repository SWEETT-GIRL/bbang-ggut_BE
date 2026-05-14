package sweetgirl.croissant.store.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;

public record DistrictStoreResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl,
		List<MenuSummaryDto> topMenus) {

	public static DistrictStoreResponse of(Long id, String name, String address, String thumbnailUrl,
			List<MenuSummaryDto> topMenus) {
		return new DistrictStoreResponse(id, name, address, thumbnailUrl, topMenus);
	}
}
