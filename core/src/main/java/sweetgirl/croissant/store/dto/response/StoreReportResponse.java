package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.enums.StoreReportStatus;

public record StoreReportResponse(Long reportId, StoreReportStatus status) {
}
