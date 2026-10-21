package sweetgirl.croissant.abuse.dto.projection;

import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;

public interface AbuseReportTargetCountProjection {

	AbuseReportTargetType getTargetType();

	Long getTargetId();

	Long getReporterCount();
}
