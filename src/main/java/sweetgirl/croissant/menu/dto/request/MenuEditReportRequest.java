package sweetgirl.croissant.menu.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import sweetgirl.croissant.store.dto.request.ReportImageRequest;

public record MenuEditReportRequest(
		@Positive Integer price,
		List<Long> deleteImageIds,
		@Valid List<ReportImageRequest> addImages,
		String reason) {
}
