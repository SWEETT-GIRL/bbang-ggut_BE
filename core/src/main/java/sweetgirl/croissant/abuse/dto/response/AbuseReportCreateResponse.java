package sweetgirl.croissant.abuse.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;

public record AbuseReportCreateResponse(
		Long reportId,
		AbuseReportStatus status,
		LocalDateTime createdAt) {

	public static AbuseReportCreateResponse from(AbuseReport report) {
		return new AbuseReportCreateResponse(report.getId(), report.getStatus(), report.getCreatedAt());
	}
}
