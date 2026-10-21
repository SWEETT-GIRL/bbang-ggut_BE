package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

public record BreadRecordSummaryResponse(
		long totalBreads,
		long totalStores,
		List<String> sidos,
		List<String> breadCategories,
		List<TopStore> topStores,
		int revisitRate) {

	public record TopStore(
			Long storeId,
			String name,
			long visits) {
	}
}
