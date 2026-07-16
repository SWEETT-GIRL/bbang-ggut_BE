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
			String region1st,
			String region2nd,
			int rank,
			List<String> thumbnailImageKeys,
			Double distanceMeter) { // 사용자 위치 기준 거리(m) — 좌표 미제공 시 null
	}
}
