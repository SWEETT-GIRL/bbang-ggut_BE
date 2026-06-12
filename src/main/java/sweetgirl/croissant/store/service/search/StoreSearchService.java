package sweetgirl.croissant.store.service.search;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.opensearch.client.OpenSearchStoreClient;
import sweetgirl.croissant.external.opensearch.dto.OpenSearchStoreSearchResult;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreSearchService {

	private final StoreSearchQueryParser storeSearchQueryParser;
	private final OpenSearchStoreClient openSearchStoreClient;
	private final StoreSearchAssembler storeSearchAssembler;

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Double latitude, Double longitude,
			SearchSort sort, Pageable pageable) {
		SearchIntent intent = storeSearchQueryParser.parse(keyword);
		OpenSearchStoreSearchResult result = openSearchStoreClient.searchStores(intent, pageable, latitude, longitude,
			sort);
		if (result.hits().isEmpty()) {
			return new SliceResponse<>(List.of(), false);
		}
		return new SliceResponse<>(storeSearchAssembler.assemble(result.hits()), result.hasNext());
	}
}
