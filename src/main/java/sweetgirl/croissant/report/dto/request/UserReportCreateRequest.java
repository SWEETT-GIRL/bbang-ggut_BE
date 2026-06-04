package sweetgirl.croissant.report.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;

public record UserReportCreateRequest(
		@NotNull ReportTargetType targetType,
		@NotNull @Positive Long targetId,
		@NotNull ReportReasonType reasonType,
		@Size(max = 500) String reasonDetail) {
}
