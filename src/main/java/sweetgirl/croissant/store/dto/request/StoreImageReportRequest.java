package sweetgirl.croissant.store.dto.request;

import java.util.List;

public record StoreImageReportRequest(
		List<Long> deleteImageIds,
		List<ReportImageRequest> addImages,
		String reason) {
}
