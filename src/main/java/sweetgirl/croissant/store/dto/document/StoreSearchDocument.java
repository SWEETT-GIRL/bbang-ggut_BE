package sweetgirl.croissant.store.dto.document;

import java.util.List;

public record StoreSearchDocument(
		Long storeId,
		String storeName,
		String address,
		GeoPoint location,
		List<SearchRegion> searchRegions,
		List<String> searchRegionAliases,
		List<String> breadTypes,
		List<MenuDocument> menus,
		Double popularityScore) {

	public record GeoPoint(
			Double lat,
			Double lon) {
	}

	public record SearchRegion(
			Long id,
			String name,
			String type,
			Double weight) {
	}

	public record MenuDocument(
			Long id,
			String name,
			String menuCategory) {
	}
}
