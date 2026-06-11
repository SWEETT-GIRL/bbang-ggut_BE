package sweetgirl.croissant.external.opensearch.document;

import java.util.List;

public record StoreDocument(
		Long storeId,
		String storeName,
		String address,
		GeoPointDto location,
		List<SearchRegionDto> searchRegions,
		List<String> searchRegionAliases,
		List<String> breadTypes,
		List<StoreMenuDto> menus,
		Double popularityScore) {
}
