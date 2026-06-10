package sweetgirl.croissant.external.opensearch.dto;

import java.util.List;

public record OpenSearchStoreSearchResult(
		List<OpenSearchStoreHit> hits,
		boolean hasNext) {

	public static OpenSearchStoreSearchResult empty() {
		return new OpenSearchStoreSearchResult(List.of(), false);
	}
}
