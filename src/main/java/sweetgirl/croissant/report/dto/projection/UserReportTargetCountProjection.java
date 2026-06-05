package sweetgirl.croissant.report.dto.projection;

import sweetgirl.croissant.report.enums.ReportTargetType;

public interface UserReportTargetCountProjection {

	ReportTargetType getTargetType();

	Long getTargetId();

	Long getReporterCount();
}
