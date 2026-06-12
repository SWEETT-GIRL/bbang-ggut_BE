package sweetgirl.croissant.external.opensearch.dto;

import java.util.List;

public record OpenSearchStoreHit(
		Long storeId,
		Double score,
		Double distanceMeters,
		List<Long> matchedMenuIds,
		List<MatchedField> matchedFields) {
}
