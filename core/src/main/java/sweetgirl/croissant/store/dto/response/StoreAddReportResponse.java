package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.enums.StoreReportStatus;

public record StoreAddReportResponse(
		Long reportId,
		StoreReportStatus status) {
}
