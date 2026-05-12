package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.common.enums.StoreReportStatus;

public record StoreReportResponse(Long reportId, StoreReportStatus status) {
}
