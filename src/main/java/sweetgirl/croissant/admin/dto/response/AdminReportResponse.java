package sweetgirl.croissant.admin.dto.response;

import java.util.List;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReportImageFile;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReportImageFile;
import sweetgirl.croissant.contribution.enums.ContributionReportType;

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

	public static AdminReportResponse ofStoreContributionReport(
			ContributionReport contributionReport,
			StoreContributionReport report,
			List<StoreContributionReportImageFile> images) {
		String kind = contributionReport.getReportType() == ContributionReportType.ADD
			? "STORE_ADD_REPORT"
			: "STORE_REPORT";
		return new AdminReportResponse(
			contributionReport.getId(),
			kind,
			report.getStore() != null ? report.getStore().getId() : null,
			report.getStore() != null ? report.getStore().getName() : report.getName(),
			contributionReport.getMember() != null ? contributionReport.getMember().getId() : null,
			report.getRequestType(),
			null,
			null,
			null,
			null,
			null,
			contributionReport.getStatus().name(),
			storeMessage(report),
			images.stream().map(StoreContributionReportImageFile::getAwsKey).toList(),
			contributionReport.getReviewedAt() != null ? contributionReport.getReviewedAt().toString() : null,
			contributionReport.getCreatedAt() != null ? contributionReport.getCreatedAt().toString() : null);
	}

	public static AdminReportResponse ofMenuContributionReport(
			ContributionReport contributionReport,
			MenuContributionReport report,
			List<MenuContributionReportImageFile> images) {
		String kind = contributionReport.getReportType() == ContributionReportType.ADD
			? "MENU_ADD_REPORT"
			: "MENU_REPORT";
		return new AdminReportResponse(
			contributionReport.getId(),
			kind,
			report.getStore().getId(),
			report.getStore().getName(),
			contributionReport.getMember() != null ? contributionReport.getMember().getId() : null,
			report.getRequestType(),
			null,
			null,
			null,
			null,
			null,
			contributionReport.getStatus().name(),
			menuMessage(report),
			images.stream().map(MenuContributionReportImageFile::getAwsKey).toList(),
			contributionReport.getReviewedAt() != null ? contributionReport.getReviewedAt().toString() : null,
			contributionReport.getCreatedAt() != null ? contributionReport.getCreatedAt().toString() : null);
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

	private static String storeMessage(StoreContributionReport report) {
		if (report.getMessage() != null) {
			return report.getMessage();
		}
		String message = nullToBlank(report.getName());
		if (report.getAddress() != null) {
			message += " / " + report.getAddress();
		}
		if (report.getReason() != null && !report.getReason().isBlank()) {
			message += " / " + report.getReason();
		}
		return message;
	}

	private static String menuMessage(MenuContributionReport report) {
		if (report.getMessage() != null) {
			return report.getMessage();
		}
		return nullToBlank(report.getName())
			+ (report.getPrice() != null ? " / " + report.getPrice() + "원" : "")
			+ (report.getDescription() != null ? " / " + report.getDescription() : "");
	}

	private static String nullToBlank(String value) {
		return value == null ? "" : value;
	}
}
