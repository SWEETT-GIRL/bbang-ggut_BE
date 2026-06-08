package sweetgirl.croissant.menu.dto.response;

import java.util.List;

public record RegionTrendResponse(
		String region3rd,
		List<CategoryTrend> categories) {
	public record CategoryTrend(
			String menuCategory,
			List<HotStore> stores) {
	}

	public record HotStore(
			Long storeId,
			String name,
			String address,
			int rank,
			List<String> imageKeys) {
	}
}
