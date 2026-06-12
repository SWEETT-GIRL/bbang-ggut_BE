package sweetgirl.croissant.admin.dto.response;

import java.util.List;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;
import sweetgirl.croissant.store.entity.StoreAddReport;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.entity.StoreReportImageFile;

public record AdminReportResponse(
		Long id,
		String kind,
		Long storeId,
		String storeName,
		Long memberId,
		String requestType,
		String targetType,
		Long targetId,
		Long reportedMemberId,
		String reasonType,
		String reasonDetail,
		String status,
		String message,
		List<String> images,
		String reviewedAt,
		String createdAt) {

	public static AdminReportResponse ofStoreReport(StoreReport report, List<StoreReportImageFile> images) {
		return ofStoreReport(null, report, images);
	}

	public static AdminReportResponse ofStoreReport(
			ContributionReport contributionReport,
			StoreReport report,
			List<StoreReportImageFile> images) {
		return new AdminReportResponse(
			contributionReport != null ? contributionReport.getId() : report.getId(),
			"STORE_REPORT",
			report.getStore().getId(),
			report.getStore().getName(),
			report.getMember() != null ? report.getMember().getId() : null,
			report.getRequestType().name(),
			null,
			null,
			null,
			null,
			null,
			contributionReport != null ? contributionReport.getStatus().name() : report.getStatus().name(),
			report.getMessage(),
			images.stream().map(StoreReportImageFile::getAwsKey).toList(),
			contributionReport != null && contributionReport.getReviewedAt() != null
				? contributionReport.getReviewedAt().toString()
				: report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			contributionReport != null && contributionReport.getCreatedAt() != null
				? contributionReport.getCreatedAt().toString()
				: report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
	}

	public static AdminReportResponse ofMenuAddReport(MenuAddReport report, List<MenuAddReportImageFile> images) {
		return ofMenuAddReport(null, report, images);
	}

	public static AdminReportResponse ofMenuAddReport(
			ContributionReport contributionReport,
			MenuAddReport report,
			List<MenuAddReportImageFile> images) {
		String message = report.getName()
			+ (report.getPrice() != null ? " / " + report.getPrice() + "원" : "")
			+ (report.getDescription() != null ? " / " + report.getDescription() : "");
		return new AdminReportResponse(
			contributionReport != null ? contributionReport.getId() : report.getId(),
			"MENU_ADD_REPORT",
			report.getStore().getId(),
			report.getStore().getName(),
			report.getMember() != null ? report.getMember().getId() : null,
			"MENU_ADD",
			null,
			null,
			null,
			null,
			null,
			contributionReport != null ? contributionReport.getStatus().name() : report.getStatus().name(),
			message,
			images.stream().map(MenuAddReportImageFile::getAwsKey).toList(),
			contributionReport != null && contributionReport.getReviewedAt() != null
				? contributionReport.getReviewedAt().toString()
				: report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			contributionReport != null && contributionReport.getCreatedAt() != null
				? contributionReport.getCreatedAt().toString()
				: report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
	}

	public static AdminReportResponse ofStoreAddReport(StoreAddReport report) {
		return ofStoreAddReport(null, report);
	}

	public static AdminReportResponse ofStoreAddReport(ContributionReport contributionReport, StoreAddReport report) {
		String message = report.getName()
			+ " / " + report.getAddress()
			+ (report.getReason() != null && !report.getReason().isBlank() ? " / " + report.getReason() : "");
		return new AdminReportResponse(
			contributionReport != null ? contributionReport.getId() : report.getId(),
			"STORE_ADD_REPORT",
			null,
			report.getName(),
			report.getMember() != null ? report.getMember().getId() : null,
			"STORE_ADD",
			null,
			null,
			null,
			null,
			null,
			contributionReport != null ? contributionReport.getStatus().name() : report.getStatus().name(),
			message,
			List.of(),
			contributionReport != null && contributionReport.getReviewedAt() != null
				? contributionReport.getReviewedAt().toString()
				: report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			contributionReport != null && contributionReport.getCreatedAt() != null
				? contributionReport.getCreatedAt().toString()
				: report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
	}

	public static AdminReportResponse ofAbuseReport(AbuseReport report) {
		return new AdminReportResponse(
			report.getId(),
			"ABUSE_REPORT",
			null,
			null,
			report.getReporter().getId(),
			report.getTargetType().name(),
			report.getTargetType().name(),
			report.getTargetId(),
			report.getReportedMember().getId(),
			report.getReasonType().name(),
			report.getReasonDetail(),
			report.getStatus().name(),
			report.getReasonDetail(),
			List.of(),
			report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
	}
}
