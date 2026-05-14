package sweetgirl.croissant.admin.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.entity.StoreReportImageFile;

public record AdminReportResponse(
		Long id,
		String kind,
		Long storeId,
		String storeName,
		Long memberId,
		String requestType,
		String status,
		String message,
		List<String> images,
		String reviewedAt,
		String createdAt) {

	public static AdminReportResponse ofStoreReport(StoreReport report, List<StoreReportImageFile> images) {
		return new AdminReportResponse(
			report.getId(),
			"STORE_REPORT",
			report.getStore().getId(),
			report.getStore().getName(),
			report.getMember() != null ? report.getMember().getId() : null,
			report.getRequestType().name(),
			report.getStatus().name(),
			report.getMessage(),
			images.stream().map(StoreReportImageFile::getAwsKey).toList(),
			report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
	}

	public static AdminReportResponse ofMenuAddReport(MenuAddReport report) {
		String message = report.getName()
			+ (report.getPrice() != null ? " / " + report.getPrice() + "원" : "")
			+ (report.getDescription() != null ? " / " + report.getDescription() : "");
		return new AdminReportResponse(
			report.getId(),
			"MENU_ADD_REPORT",
			report.getStore().getId(),
			report.getStore().getName(),
			report.getMember() != null ? report.getMember().getId() : null,
			"MENU_ADD",
			report.getStatus().name(),
			message,
			List.of(),
			report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
	}
}
