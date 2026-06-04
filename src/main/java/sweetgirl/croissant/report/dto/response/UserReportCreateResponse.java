package sweetgirl.croissant.report.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.UserReportStatus;

public record UserReportCreateResponse(
		Long reportId,
		UserReportStatus status,
		LocalDateTime createdAt) {

	public static UserReportCreateResponse from(UserReport report) {
		return new UserReportCreateResponse(report.getId(), report.getStatus(), report.getCreatedAt());
	}
}
